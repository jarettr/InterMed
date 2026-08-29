use super::*;

pub(crate) fn scalar_to_value(s: &ScalarValue) -> Value {
    match s {
        ScalarValue::Str(s) => Value::Str(s.clone()),
        ScalarValue::Int(i) => Value::Int(*i),
        ScalarValue::Float(f) => Value::Float(*f),
        ScalarValue::Bool(b) => Value::Bool(*b),
    }
}

/// Equality with the declarative engine's stringly semantics: a `Null` (absent
/// attribute) never equals a literal, otherwise values compare by their display
/// string (matching `term_value` / `attr_value_string` in the rules interpreter), so
/// the IR engine selects exactly the facts the interpreter's `where_all`/`where_not`
/// would. Numeric comparisons (below) stay typed.
pub(super) fn value_eq(lhs: &Value, rhs: &Value) -> bool {
    if lhs.is_null() || rhs.is_null() {
        return false;
    }
    lhs == rhs || lhs.to_display() == rhs.to_display()
}

pub(crate) fn eval_cmp(lhs: &Value, op: CmpOp, rhs: &Value) -> bool {
    match op {
        CmpOp::Eq => value_eq(lhs, rhs),
        CmpOp::Ne => !lhs.is_null() && !rhs.is_null() && !value_eq(lhs, rhs),
        CmpOp::Lt => matches!(lhs.partial_cmp_value(rhs), Some(Ordering::Less)),
        CmpOp::Le => matches!(
            lhs.partial_cmp_value(rhs),
            Some(Ordering::Less | Ordering::Equal)
        ),
        CmpOp::Gt => matches!(lhs.partial_cmp_value(rhs), Some(Ordering::Greater)),
        CmpOp::Ge => matches!(
            lhs.partial_cmp_value(rhs),
            Some(Ordering::Greater | Ordering::Equal)
        ),
    }
}

/// Type-safe composite group key. Non-null cells retain the declarative engine's
/// display-string equality (so `Int(2)` and `Str("2")` still group together), but
/// the vector shape and explicit null cell avoid delimiter collisions and the
/// allocation-heavy `join` previously used here.
pub(super) fn group_key(tuple: &Tuple, positions: &[Option<usize>]) -> Vec<HashKey> {
    positions
        .iter()
        .map(|p| {
            p.and_then(|i| tuple.get(i))
                .filter(|value| !value.is_null())
                .map(|value| HashKey::Str(value.to_display()))
                .unwrap_or(HashKey::Null)
        })
        .collect()
}

/// Hash aggregation. Groups are accumulated in a hash table keyed by the group
/// columns; first-seen group order is preserved for deterministic output.
pub(super) fn hash_aggregate<'a>(
    input: &'a PhysicalPlan,
    group_by: &'a [String],
    aggregates: &'a [Aggregate],
    store: &'a ColumnarStore,
    registry: &'a FunctionRegistry,
) -> Result<RowStream<'a>, ColumnarError> {
    let inner = stream(input, store, registry)?;
    let group_pos: Vec<Option<usize>> = group_by.iter().map(|c| inner.schema.pos(c)).collect();
    let agg_pos: Vec<Option<usize>> = aggregates
        .iter()
        .map(|a| inner.schema.pos(&a.column))
        .collect();

    // Group key → slot in `order`, so output is first-seen order.
    let mut index: AHashMap<Vec<HashKey>, usize> = AHashMap::new();
    let mut order: Vec<Vec<Tuple>> = Vec::new();
    for tuple in inner.iter {
        let key = group_key(&tuple, &group_pos);
        match index.get(&key) {
            Some(&slot) => order[slot].push(tuple),
            None => {
                index.insert(key, order.len());
                order.push(vec![tuple]);
            }
        }
    }

    let out_rows: Vec<Tuple> = order
        .into_iter()
        .map(|members| {
            let first = &members[0];
            let mut tuple: Tuple = group_pos
                .iter()
                .map(|p| p.and_then(|i| first.get(i).cloned()).unwrap_or(Value::Null))
                .collect();
            for (agg, pos) in aggregates.iter().zip(&agg_pos) {
                tuple.push(compute_agg(agg, &members, *pos)?);
            }
            Ok(tuple)
        })
        .collect::<Result<Vec<Tuple>, ColumnarError>>()?;

    let mut names: Vec<String> = group_by.to_vec();
    names.extend(aggregates.iter().map(|a| a.alias.clone()));
    Ok(RowStream {
        schema: Arc::new(Schema::new(names)),
        iter: Box::new(out_rows.into_iter()),
    })
}

pub(super) fn compute_agg(
    agg: &Aggregate,
    members: &[Tuple],
    pos: Option<usize>,
) -> Result<Value, ColumnarError> {
    match agg.func {
        AggFunc::Count => Ok(Value::Int(members.len() as i64)),
        AggFunc::Sum | AggFunc::Avg | AggFunc::Min | AggFunc::Max => {
            let nums: Vec<f64> = members
                .iter()
                .filter_map(|m| pos.and_then(|i| m.get(i)).and_then(Value::as_f64))
                .collect();
            if nums.is_empty() {
                return Ok(Value::Null);
            }
            let v = match agg.func {
                AggFunc::Sum => nums.iter().sum(),
                AggFunc::Avg => nums.iter().sum::<f64>() / nums.len() as f64,
                AggFunc::Min => nums.iter().cloned().fold(f64::INFINITY, f64::min),
                AggFunc::Max => nums.iter().cloned().fold(f64::NEG_INFINITY, f64::max),
                // Unreachable given the outer arm, but a planner bug should surface
                // as a query error, not a panic.
                AggFunc::Count => {
                    return Err(ColumnarError::Internal(
                        "Count reached numeric aggregate path".into(),
                    ));
                }
            };
            Ok(Value::Float(v))
        }
    }
}

/// Compute window functions per partition. Output = each input tuple with one value
/// appended per [`WindowFunction`] (so the row count is unchanged). Rows are emitted
/// partition-by-partition, each partition ordered by the `order_by` columns.
pub(super) fn compute_window(
    rows: Vec<Tuple>,
    ppos: &[Option<usize>],
    opos: &[Option<usize>],
    fpos: &[Option<usize>],
    functions: &[WindowFunction],
) -> Result<Vec<Tuple>, ColumnarError> {
    // Partition rows by the partition-by key (first-seen order).
    let mut index: AHashMap<Vec<HashKey>, usize> = AHashMap::new();
    let mut parts: Vec<Vec<Tuple>> = Vec::new();
    for row in rows {
        let key = group_key(&row, ppos);
        match index.get(&key) {
            Some(&slot) => parts[slot].push(row),
            None => {
                index.insert(key, parts.len());
                parts.push(vec![row]);
            }
        }
    }

    let mut out = Vec::new();
    for mut part in parts {
        part.sort_by(|a, b| cmp_by_positions(a, b, opos));
        // Order keys for rank/dense_rank (ties share a rank).
        let order_keys: Vec<Vec<Value>> = part
            .iter()
            .map(|t| {
                opos.iter()
                    .map(|p| p.and_then(|i| t.get(i)).cloned().unwrap_or(Value::Null))
                    .collect()
            })
            .collect();
        let n = part.len();
        let mut rank = vec![1usize; n];
        let mut dense = vec![1usize; n];
        for i in 1..n {
            if order_keys[i] == order_keys[i - 1] {
                rank[i] = rank[i - 1];
                dense[i] = dense[i - 1];
            } else {
                rank[i] = i + 1;
                dense[i] = dense[i - 1] + 1;
            }
        }
        let partition_values: Vec<Option<Value>> = functions
            .iter()
            .zip(fpos)
            .map(|(f, &fp)| match f.func {
                WindowFn::Sum | WindowFn::Avg | WindowFn::Min | WindowFn::Max => {
                    window_agg(f.func, &part, fp).map(Some)
                }
                _ => Ok(None),
            })
            .collect::<Result<_, ColumnarError>>()?;
        for (idx, row) in part.iter().enumerate() {
            let mut tuple = row.clone();
            for (fn_idx, f) in functions.iter().enumerate() {
                let v = match f.func {
                    WindowFn::RowNumber => Value::Int((idx + 1) as i64),
                    WindowFn::Rank => Value::Int(rank[idx] as i64),
                    WindowFn::DenseRank => Value::Int(dense[idx] as i64),
                    WindowFn::Count => Value::Int(part.len() as i64),
                    WindowFn::Sum | WindowFn::Avg | WindowFn::Min | WindowFn::Max => {
                        partition_values[fn_idx].clone().unwrap_or(Value::Null)
                    }
                };
                tuple.push(v);
            }
            out.push(tuple);
        }
    }
    Ok(out)
}

/// Lexicographic comparison of two tuples over the given column positions (numeric
/// values compare numerically; incomparable/absent compare equal so the sort is total
/// and stable).
pub(super) fn cmp_by_positions(a: &Tuple, b: &Tuple, positions: &[Option<usize>]) -> Ordering {
    for &p in positions {
        if let (Some(x), Some(y)) = (p.and_then(|i| a.get(i)), p.and_then(|i| b.get(i))) {
            match x.partial_cmp_value(y) {
                Some(o) if o != Ordering::Equal => return o,
                _ => {}
            }
        }
    }
    Ordering::Equal
}

/// A whole-partition aggregate window (`Sum`/`Avg`/`Min`/`Max`) over column `fp`.
pub(super) fn window_agg(
    func: WindowFn,
    part: &[Tuple],
    fp: Option<usize>,
) -> Result<Value, ColumnarError> {
    let nums: Vec<f64> = part
        .iter()
        .filter_map(|t| fp.and_then(|i| t.get(i)).and_then(Value::as_f64))
        .collect();
    if nums.is_empty() {
        return Ok(Value::Null);
    }
    let v = match func {
        WindowFn::Sum => nums.iter().sum(),
        WindowFn::Avg => nums.iter().sum::<f64>() / nums.len() as f64,
        WindowFn::Min => nums.iter().cloned().fold(f64::INFINITY, f64::min),
        WindowFn::Max => nums.iter().cloned().fold(f64::NEG_INFINITY, f64::max),
        // Unreachable given the caller's dispatch, but a planner bug should
        // surface as a query error, not a panic.
        _ => {
            return Err(ColumnarError::Internal(
                "non-aggregate window fn reached window_agg".into(),
            ));
        }
    };
    Ok(Value::Float(v))
}
