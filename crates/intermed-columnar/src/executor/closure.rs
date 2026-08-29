use super::*;

/// In-process transitive closure over a `(from, to)` edge relation. Semi-naïve
/// fixpoint — correct for the recursive reachability the plan routes to Souffle.
pub(super) fn transitive_closure<'a>(
    rows: impl Iterator<Item = Tuple> + 'a,
    from: Option<usize>,
    to: Option<usize>,
) -> Vec<Tuple> {
    let mut closure: AHashSet<(String, String)> = AHashSet::new();
    let mut adjacency: AHashMap<String, AHashSet<String>> = AHashMap::new();
    if let (Some(fi), Some(ti)) = (from, to) {
        for tuple in rows {
            let (Some(a), Some(b)) = (tuple.get(fi), tuple.get(ti)) else {
                continue;
            };
            if a.is_null() || b.is_null() {
                continue;
            }
            let (a, b) = (a.to_display(), b.to_display());
            adjacency.entry(a.clone()).or_default().insert(b.clone());
            closure.insert((a, b));
        }
    }
    let mut delta = closure.clone();
    while !delta.is_empty() {
        let mut added = Vec::new();
        for (a, b) in &delta {
            if let Some(targets) = adjacency.get(b) {
                for d in targets {
                    if !closure.contains(&(a.clone(), d.clone())) {
                        added.push((a.clone(), d.clone()));
                    }
                }
            }
        }
        if added.is_empty() {
            break;
        }
        delta = added.into_iter().collect();
        closure.extend(delta.iter().cloned());
    }
    // Deterministic output order (the set is unordered).
    let mut pairs: Vec<(String, String)> = closure.into_iter().collect();
    pairs.sort();
    pairs
        .into_iter()
        .map(|(a, b)| vec![Value::Str(a), Value::Str(b)])
        .collect()
}
