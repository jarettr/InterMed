package org.intermed.harness.report;

import org.intermed.harness.analysis.IssueRecord;
import org.intermed.harness.runner.TestCase;
import org.intermed.harness.runner.TestResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Generates a self-contained, single-file HTML compatibility report with:
 * <ul>
 *   <li>Summary KPI cards</li>
 *   <li>Canvas charts: outcome donut, startup-time histogram, failure breakdown bar</li>
 *   <li>Metrics table: p50 / p90 / p95 / p99 startup times, loader comparison</li>
 *   <li>Top slowest mods + top issue tags tables</li>
 *   <li>Filterable / sortable results table with expandable log snippets</li>
 *   <li>CSV export button</li>
 * </ul>
 * No external CDN dependencies — all CSS and JS are inlined.
 */
public final class HtmlReportWriter {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final int[] HISTOGRAM_EDGES_SECS = {15, 30, 45, 60, 90, 120};

    public Path write(CompatibilityMatrix matrix, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path dest = reportDir.resolve("index.html");
        Files.writeString(dest, buildHtml(matrix));
        System.out.println("[Report] HTML written: " + dest);
        return dest;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Top-level assembly
    // ══════════════════════════════════════════════════════════════════════════

    private String buildHtml(CompatibilityMatrix m) {
        // Pre-compute all metrics once
        long[] hist     = m.startupHistogram(HISTOGRAM_EDGES_SECS);
        Map<TestResult.Outcome, Long> byOutcome = m.countByOutcome();
        Map<String, Long> topTags = m.topIssueTags(10);
        List<TestResult> slowest  = m.topSlowest(15);

        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n"
            + "<meta charset=\"UTF-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>InterMed Compatibility Report</title>\n"
            + "<style>" + CSS + "</style>\n"
            + "</head>\n<body>\n"
            + buildHeader(m)
            + buildKpiBar(m)
            + buildChartsRow(m, byOutcome, hist)
            + buildMetricsRow(m, topTags, slowest)
            + buildControls()
            + buildTable(m.all())
            + buildFooter(m)
            + "<script>" + buildScript(m.all(), byOutcome, hist) + "</script>\n"
            + "</body></html>\n";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HTML sections
    // ══════════════════════════════════════════════════════════════════════════

    private String buildHeader(CompatibilityMatrix m) {
        return "<header>\n"
            + "<div class=\"hdr-title\"><span class=\"logo\">⬡</span> InterMed v8.0 — Compatibility Report</div>\n"
            + "<div class=\"hdr-meta\">"
            + "Generated: " + FMT.format(Instant.now())
            + " &nbsp;·&nbsp; MC 1.20.1"
            + " &nbsp;·&nbsp; " + m.totalCount() + " test cases"
            + "</div>\n"
            + "</header>\n";
    }

    private String buildKpiBar(CompatibilityMatrix m) {
        String passRate = String.format("%.1f%%", m.passRate());
        String avgTime  = m.avgStartupMs() > 0
            ? String.format("%.1fs", m.avgStartupMs() / 1000.0) : "—";
        String p95Time  = m.startupPercentileMs(95) > 0
            ? String.format("%.1fs", m.startupPercentileMs(95) / 1000.0) : "—";

        return "<div class=\"kpi-bar\">\n"
            + kpi("Total", m.totalCount(), "blue")
            + kpi("Pass", m.passCount(), "green")
            + kpi("Bridged", m.bridgedCount(), "sky")
            + kpi("Perf Warn", m.perfWarnCount(), "yellow")
            + kpi("Fail", m.failCount(), "red")
            + kpi("Pass Rate", passRate, m.passRate() >= 80 ? "green" : m.passRate() >= 50 ? "yellow" : "red")
            + kpi("Avg Start", avgTime, "purple")
            + kpi("p95 Start", p95Time, "purple")
            + "</div>\n";
    }

    private String kpi(String label, Object value, String color) {
        return "<div class=\"kpi\"><div class=\"kpi-lbl\">" + label + "</div>"
            + "<div class=\"kpi-val " + color + "\">" + value + "</div></div>\n";
    }

    private String buildChartsRow(CompatibilityMatrix m,
                                   Map<TestResult.Outcome, Long> byOutcome,
                                   long[] hist) {
        // Donut legend
        StringBuilder legend = new StringBuilder();
        for (TestResult.Outcome o : TestResult.Outcome.values()) {
            long cnt = byOutcome.getOrDefault(o, 0L);
            if (cnt == 0) continue;
            legend.append("<div class=\"leg-item\">")
                  .append("<span class=\"leg-dot badge ").append(o.cssClass()).append("\"></span>")
                  .append("<span>").append(o.name().replace("_", " ")).append(" (").append(cnt).append(")</span>")
                  .append("</div>\n");
        }

        // Histogram x-labels
        StringBuilder histLabels = new StringBuilder("[");
        String[] edges = {"0-15s","15-30s","30-45s","45-60s","60-90s","90-120s","120s+"};
        for (int i = 0; i < edges.length; i++) {
            if (i > 0) histLabels.append(",");
            histLabels.append("\"").append(edges[i]).append("\"");
        }
        histLabels.append("]");

        StringBuilder histValues = new StringBuilder("[");
        for (int i = 0; i < hist.length; i++) {
            if (i > 0) histValues.append(",");
            histValues.append(hist[i]);
        }
        histValues.append("]");

        // Failure bar data
        StringBuilder failLabels = new StringBuilder("[");
        StringBuilder failValues = new StringBuilder("[");
        boolean firstFail = true;
        for (TestResult.Outcome o : TestResult.Outcome.values()) {
            if (!o.isFailing()) continue;
            long cnt = byOutcome.getOrDefault(o, 0L);
            if (cnt == 0) continue;
            if (!firstFail) { failLabels.append(","); failValues.append(","); }
            failLabels.append("\"").append(o.name().replace("FAIL_", "")).append("\"");
            failValues.append(cnt);
            firstFail = false;
        }
        failLabels.append("]");
        failValues.append("]");

        return "<div class=\"charts-row\">\n"
            // Donut
            + "<div class=\"chart-card\">\n"
            + "<div class=\"chart-title\">Outcome Distribution</div>\n"
            + "<canvas id=\"donutChart\" width=\"220\" height=\"220\"></canvas>\n"
            + "<div class=\"legend\">" + legend + "</div>\n"
            + "</div>\n"
            // Histogram
            + "<div class=\"chart-card\">\n"
            + "<div class=\"chart-title\">Startup Time Distribution (passing mods)</div>\n"
            + "<canvas id=\"histChart\" width=\"420\" height=\"240\"></canvas>\n"
            + "<div id=\"histData\" data-labels='" + histLabels + "' data-values='" + histValues + "' hidden></div>\n"
            + "</div>\n"
            // Failure breakdown
            + "<div class=\"chart-card\">\n"
            + "<div class=\"chart-title\">Failure Breakdown</div>\n"
            + "<canvas id=\"failChart\" width=\"340\" height=\"240\"></canvas>\n"
            + "<div id=\"failData\" data-labels='" + failLabels + "' data-values='" + failValues + "' hidden></div>\n"
            + "</div>\n"
            + "</div>\n";
    }

    private String buildMetricsRow(CompatibilityMatrix m,
                                    Map<String, Long> topTags,
                                    List<TestResult> slowest) {
        // Percentile table
        String percTable = "<table class=\"inner-tbl\">\n"
            + "<tr><th>Metric</th><th>Value</th></tr>\n"
            + percRow("Min", m.minStartupMs())
            + percRow("p50 (median)", m.startupPercentileMs(50))
            + percRow("p75", m.startupPercentileMs(75))
            + percRow("p90", m.startupPercentileMs(90))
            + percRow("p95", m.startupPercentileMs(95))
            + percRow("p99", m.startupPercentileMs(99))
            + percRow("Max", m.maxStartupMs())
            + "<tr><td>Average</td><td>" + String.format("%.1fs", m.avgStartupMs()/1000.0) + "</td></tr>\n"
            + "</table>";

        // Loader comparison
        StringBuilder loaderRows = new StringBuilder();
        for (TestCase.Loader loader : TestCase.Loader.values()) {
            loaderRows.append("<tr><td><span class=\"loader-badge\">")
                .append(loader.name())
                .append("</span></td>")
                .append("<td>").append(m.countForLoader(loader)).append("</td>")
                .append("<td class=\"").append(passRateClass(m.passRateForLoader(loader))).append("\">")
                .append(String.format("%.1f%%", m.passRateForLoader(loader))).append("</td></tr>\n");
        }
        String loaderTable = "<table class=\"inner-tbl\">\n"
            + "<tr><th>Loader</th><th>Tests</th><th>Pass Rate</th></tr>\n"
            + loaderRows
            + "</table>";

        // Top issue tags
        StringBuilder tagRows = new StringBuilder();
        topTags.forEach((tag, count) ->
            tagRows.append("<tr><td><code>").append(escHtml(tag)).append("</code></td>")
                   .append("<td>").append(count).append("</td></tr>\n"));
        String tagTable = "<table class=\"inner-tbl\">\n"
            + "<tr><th>Issue Tag</th><th>Count</th></tr>\n"
            + tagRows
            + "</table>";

        // Slowest mods table
        StringBuilder slowRows = new StringBuilder();
        for (TestResult r : slowest) {
            String mod = r.testCase().mods().isEmpty() ? "—"
                : escHtml(r.testCase().mods().get(0).name());
            String loader = r.testCase().loader().name();
            String time = String.format("%.1fs", r.startupMs() / 1000.0);
            slowRows.append("<tr><td>").append(mod).append("</td>")
                .append("<td><span class=\"loader-badge\">").append(loader).append("</span></td>")
                .append("<td class=\"mono\">").append(time).append("</td>")
                .append("<td><span class=\"badge ").append(r.outcome().cssClass()).append("\">")
                .append(r.outcome().name().replace("_"," ")).append("</span></td></tr>\n");
        }
        String slowTable = "<table class=\"inner-tbl\">\n"
            + "<tr><th>Mod</th><th>Loader</th><th>Time</th><th>Outcome</th></tr>\n"
            + slowRows
            + "</table>";

        return "<div class=\"metrics-row\">\n"
            + "<div class=\"metrics-card\"><div class=\"chart-title\">Startup Time Percentiles</div>" + percTable + "</div>\n"
            + "<div class=\"metrics-card\"><div class=\"chart-title\">Loader Comparison</div>" + loaderTable + "</div>\n"
            + "<div class=\"metrics-card\"><div class=\"chart-title\">Top Issue Tags</div>" + tagTable + "</div>\n"
            + "<div class=\"metrics-card wide\"><div class=\"chart-title\">Slowest Passing Mods (top 15)</div>" + slowTable + "</div>\n"
            + "</div>\n";
    }

    private String percRow(String label, long ms) {
        String val = ms > 0 ? String.format("%.1fs", ms / 1000.0) : "—";
        return "<tr><td>" + label + "</td><td class=\"mono\">" + val + "</td></tr>\n";
    }

    private String passRateClass(double rate) {
        if (rate >= 80) return "rate-good";
        if (rate >= 50) return "rate-warn";
        return "rate-bad";
    }

    private String buildControls() {
        return "<div class=\"controls\">\n"
            + "<input type=\"text\" id=\"search\" placeholder=\"Search mod name, slug, or issue…\" oninput=\"filterTable()\">\n"
            + "<select id=\"outcomeFilter\" onchange=\"filterTable()\">\n"
            + "<option value=\"\">All outcomes</option>\n"
            + "<option value=\"PASS\">Pass</option>\n"
            + "<option value=\"PASS_BRIDGED\">Bridged</option>\n"
            + "<option value=\"PERF_WARN\">Perf Warn</option>\n"
            + "<option value=\"FAIL_TIMEOUT\">Timeout</option>\n"
            + "<option value=\"FAIL_CRASH\">Crash</option>\n"
            + "<option value=\"FAIL_MIXIN\">Mixin Conflict</option>\n"
            + "<option value=\"FAIL_DEPENDENCY\">Missing Dependency</option>\n"
            + "<option value=\"FAIL_CAPABILITY\">Capability Denied</option>\n"
            + "<option value=\"FAIL_OTHER\">Other Fail</option>\n"
            + "</select>\n"
            + "<select id=\"loaderFilter\" onchange=\"filterTable()\">\n"
            + "<option value=\"\">All loaders</option>\n"
            + "<option value=\"FORGE\">Forge</option>\n"
            + "<option value=\"FABRIC\">Fabric</option>\n"
            + "<option value=\"NEOFORGE\">NeoForge</option>\n"
            + "</select>\n"
            + "<button onclick=\"exportCsv()\">⬇ Export CSV</button>\n"
            + "<span id=\"rowCount\" class=\"row-count\"></span>\n"
            + "</div>\n";
    }

    private String buildTable(List<TestResult> results) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            rows.append(buildRow(results.get(i), i));
        }
        return "<div class=\"tbl-wrap\">\n"
            + "<table id=\"resultsTable\">\n"
            + "<thead><tr>"
            + th("Mod(s)", 0) + th("Loader", 1) + th("Version", 2)
            + th("Downloads", 3) + th("Outcome", 4) + th("Startup", 5) + th("Issues", 6)
            + "</tr></thead>\n"
            + "<tbody id=\"tbody\">\n"
            + rows
            + "</tbody></table>\n"
            + "<div class=\"no-results\" id=\"noResults\" style=\"display:none\">No results match your filters.</div>\n"
            + "</div>\n";
    }

    private String th(String label, int col) {
        return "<th onclick=\"sortTable(" + col + ")\">" + label + "</th>";
    }

    private String buildRow(TestResult r, int idx) {
        String outcome = r.outcome().name();
        String css     = r.outcome().cssClass();

        // Mod names + modrinth links
        StringBuilder modCell = new StringBuilder();
        long totalDownloads = 0;
        StringBuilder verCell = new StringBuilder();
        for (var mod : r.testCase().mods()) {
            modCell.append("<div class=\"mod-name\">")
                   .append(escHtml(mod.name()))
                   .append(" <a class=\"mr-link\" href=\"").append(mod.modrinthUrl())
                   .append("\" target=\"_blank\" rel=\"noopener\">↗ ").append(escHtml(mod.slug())).append("</a>")
                   .append("</div>");
            verCell.append("<div class=\"dim\">").append(escHtml(mod.versionNumber())).append("</div>");
            totalDownloads += mod.downloads();
        }

        // Log toggle
        String logSnippet = r.logSnippet(5000);
        String logId = "log-" + idx;
        String logHtml = "";
        if (logSnippet != null && !logSnippet.isBlank()) {
            logHtml = "<span class=\"log-toggle\" onclick=\"toggleLog('" + logId + "')\">▶ log</span>"
                + "<div class=\"log-box\" id=\"" + logId + "\">" + escHtml(logSnippet) + "</div>";
        }

        // Issues (non-INFO only)
        StringBuilder issCell = new StringBuilder();
        for (IssueRecord issue : r.issues()) {
            if (issue.severity() == IssueRecord.Severity.INFO) continue;
            issCell.append("<div class=\"issue iss-").append(issue.severity().name().toLowerCase()).append("\">")
                   .append("• ").append(escHtml(issue.description())).append("</div>");
        }

        String startup = r.startupMs() > 0
            ? String.format("%.1fs", r.startupMs() / 1000.0) : "—";

        return "<tr data-outcome=\"" + outcome + "\""
            + " data-loader=\"" + r.testCase().loader().name() + "\""
            + " data-id=\"" + escHtml(r.testCase().id()) + "\""
            + " data-search=\"" + escHtml(buildSearchText(r)) + "\">\n"
            + "<td>" + modCell + logHtml + "</td>\n"
            + "<td><span class=\"loader-badge\">" + r.testCase().loader().name() + "</span></td>\n"
            + "<td>" + verCell + "</td>\n"
            + "<td class=\"mono\">" + fmtDownloads(totalDownloads) + "</td>\n"
            + "<td><span class=\"badge " + css + "\">" + outcome.replace("_", " ") + "</span></td>\n"
            + "<td class=\"mono\">" + startup + "</td>\n"
            + "<td>" + issCell + "</td>\n"
            + "</tr>\n";
    }

    private String buildFooter(CompatibilityMatrix m) {
        return "<footer>"
            + "InterMed Test Harness &nbsp;·&nbsp; "
            + m.totalCount() + " tests &nbsp;·&nbsp; "
            + FMT.format(Instant.now())
            + "</footer>\n";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JavaScript
    // ══════════════════════════════════════════════════════════════════════════

    private String buildScript(List<TestResult> results,
                                Map<TestResult.Outcome, Long> byOutcome,
                                long[] hist) {

        // Build donut data JSON
        StringBuilder donutData = new StringBuilder("[");
        String[] donutColors = {
            "#4ade80","#60a5fa","#facc15","#fb923c",
            "#f87171","#c084fc","#d4d400","#e879f9","#94a3b8"
        };
        int ci = 0;
        for (TestResult.Outcome o : TestResult.Outcome.values()) {
            long cnt = byOutcome.getOrDefault(o, 0L);
            if (ci > 0) donutData.append(",");
            donutData.append("{\"v\":").append(cnt)
                     .append(",\"c\":\"").append(donutColors[ci % donutColors.length]).append("\"")
                     .append(",\"l\":\"").append(o.name().replace("_", " ")).append("\"}");
            ci++;
        }
        donutData.append("]");

        return ("""
// ── Chart colours ────────────────────────────────────────────────────────────
const OUTCOME_COLORS={
  PASS:'#4ade80',PASS_BRIDGED:'#60a5fa',PERF_WARN:'#facc15',
  FAIL_TIMEOUT:'#fb923c',FAIL_CRASH:'#f87171',FAIL_MIXIN:'#c084fc',
  FAIL_DEPENDENCY:'#d4d400',FAIL_CAPABILITY:'#e879f9',FAIL_OTHER:'#94a3b8'
};
const DONUT_DATA=/*DD*/[];
""").replace("/*DD*/[]", donutData.toString()) + """

// ── Helpers ───────────────────────────────────────────────────────────────────
function parseAttr(id,attr){
  var el=document.getElementById(id);
  if(!el)return[];
  try{return JSON.parse(el.getAttribute(attr));}catch(e){return[];}
}

// ── Donut chart ───────────────────────────────────────────────────────────────
function drawDonut(){
  var c=document.getElementById('donutChart');
  if(!c)return;
  var ctx=c.getContext('2d');
  var w=c.width,h=c.height,cx=w/2,cy=h/2;
  var r=Math.min(cx,cy)-14;
  var total=DONUT_DATA.reduce(function(s,d){return s+d.v;},0);
  if(total===0){
    ctx.fillStyle='#2d3148';ctx.beginPath();ctx.arc(cx,cy,r,0,Math.PI*2);ctx.fill();
    return;
  }
  var angle=-Math.PI/2;
  DONUT_DATA.forEach(function(d){
    if(!d.v)return;
    var sweep=(d.v/total)*Math.PI*2;
    ctx.beginPath();ctx.moveTo(cx,cy);
    ctx.arc(cx,cy,r,angle,angle+sweep);
    ctx.fillStyle=d.c;ctx.fill();
    angle+=sweep;
  });
  ctx.beginPath();ctx.arc(cx,cy,r*0.55,0,Math.PI*2);
  ctx.fillStyle='#0f1117';ctx.fill();
  ctx.textAlign='center';ctx.textBaseline='middle';
  ctx.fillStyle='#e2e8f0';ctx.font='bold 22px system-ui';
  ctx.fillText(total,cx,cy-10);
  ctx.font='11px system-ui';ctx.fillStyle='#94a3b8';
  ctx.fillText('tests',cx,cy+10);
}

// ── Bar chart ─────────────────────────────────────────────────────────────────
function drawBars(canvasId,dataId,barColor){
  var c=document.getElementById(canvasId);
  if(!c)return;
  var labels=parseAttr(dataId,'data-labels');
  var values=parseAttr(dataId,'data-values');
  if(!labels.length)return;
  var ctx=c.getContext('2d');
  var w=c.width,h=c.height;
  var pad={top:24,right:12,bottom:46,left:38};
  var cw=w-pad.left-pad.right,ch=h-pad.top-pad.bottom;
  var max=Math.max.apply(null,values.concat([1]));
  var barW=cw/labels.length*0.65,gap=cw/labels.length;

  // Grid lines
  ctx.strokeStyle='#1e2235';ctx.lineWidth=1;
  for(var g=0;g<=4;g++){
    var gy=pad.top+ch*(1-g/4);
    ctx.beginPath();ctx.moveTo(pad.left,gy);ctx.lineTo(w-pad.right,gy);ctx.stroke();
    ctx.fillStyle='#4a5568';ctx.font='9px system-ui';ctx.textAlign='right';
    ctx.fillText(Math.round(max*g/4),pad.left-4,gy+3);
  }

  // Bars
  for(var i=0;i<values.length;i++){
    var v=values[i];
    var x=pad.left+i*gap+(gap-barW)/2;
    var bh=(v/max)*ch;
    var by=pad.top+ch-bh;
    var color=Array.isArray(barColor)?barColor[i]:barColor;
    ctx.fillStyle=color||'#6366f1';
    ctx.fillRect(x,by,barW,bh);
    if(v>0){
      ctx.fillStyle='#e2e8f0';ctx.font='bold 10px system-ui';ctx.textAlign='center';
      ctx.fillText(v,x+barW/2,by-5);
    }
    // X label (wrap at dash)
    ctx.fillStyle='#64748b';ctx.font='9px system-ui';ctx.textAlign='center';
    var lbl=labels[i];
    ctx.fillText(lbl,x+barW/2,h-pad.bottom+14);
  }
}

function drawFailBars(){
  var failColors=[];
  var labels=parseAttr('failData','data-labels');
  var colorMap={CRASH:'#f87171',MIXIN:'#c084fc',DEPENDENCY:'#d4d400',
                CAPABILITY:'#e879f9',TIMEOUT:'#fb923c',OTHER:'#94a3b8'};
  labels.forEach(function(l){failColors.push(colorMap[l]||'#6366f1');});
  drawBars('failChart','failData',failColors);
}

// ── Table filter / sort ───────────────────────────────────────────────────────
function filterTable(){
  var s=document.getElementById('search').value.toLowerCase();
  var oc=document.getElementById('outcomeFilter').value;
  var ldr=document.getElementById('loaderFilter').value;
  var rows=document.querySelectorAll('#tbody tr');
  var n=0;
  rows.forEach(function(row){
    var ok=(!s||row.dataset.search.includes(s))
          &&(!oc||row.dataset.outcome===oc)
          &&(!ldr||row.dataset.loader===ldr);
    row.style.display=ok?'':'none';
    if(ok)n++;
  });
  document.getElementById('rowCount').textContent=n+' / '+rows.length+' shown';
  document.getElementById('noResults').style.display=n===0?'block':'none';
}

var _sortDir={};
function sortTable(col){
  var tbody=document.getElementById('tbody');
  var rows=Array.from(tbody.querySelectorAll('tr'));
  var dir=(_sortDir[col]||'asc')==='asc'?'desc':'asc';
  _sortDir[col]=dir;
  rows.sort(function(a,b){
    var av=a.cells[col]?a.cells[col].textContent.trim():'';
    var bv=b.cells[col]?b.cells[col].textContent.trim():'';
    var n=parseFloat(av.replace(/[^0-9.]/g,''))-parseFloat(bv.replace(/[^0-9.]/g,''));
    if(!isNaN(n))return dir==='asc'?n:-n;
    return dir==='asc'?av.localeCompare(bv):bv.localeCompare(av);
  });
  rows.forEach(function(r){tbody.appendChild(r);});
  document.querySelectorAll('th').forEach(function(th,i){
    th.classList.remove('sorted-asc','sorted-desc');
    if(i===col)th.classList.add('sorted-'+dir);
  });
}

function toggleLog(id){
  var el=document.getElementById(id);
  var tog=el.previousElementSibling;
  if(!el||!tog)return;
  var open=el.style.display==='block';
  el.style.display=open?'none':'block';
  tog.textContent=open?'▶ log':'▼ log';
}

// ── CSV export ────────────────────────────────────────────────────────────────
function exportCsv(){
  var rows=document.querySelectorAll('#tbody tr');
  var lines=['"id","loader","mod","outcome","startup_s","issues"'];
  rows.forEach(function(row){
    var id=row.dataset.id||'';
    var ldr=row.dataset.loader||'';
    var oc=row.dataset.outcome||'';
    var mod=row.cells[0]?row.cells[0].innerText.split('\\n')[0].replace(/,/g,';'):'';
    var st=row.cells[5]?row.cells[5].textContent.trim():'';
    var iss=row.cells[6]?row.cells[6].textContent.replace(/\\n/g,' ').replace(/,/g,';'):'';
    lines.push([id,ldr,mod,oc,st,iss].map(function(v){return '"'+v+'"';}).join(','));
  });
  var blob=new Blob([lines.join('\\n')],{type:'text/csv'});
  var a=document.createElement('a');a.href=URL.createObjectURL(blob);
  a.download='intermed-compat.csv';a.click();
}

// ── Init ──────────────────────────────────────────────────────────────────────
drawDonut();
drawBars('histChart','histData','#6366f1');
drawFailBars();
filterTable();
""";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════════

    private String buildSearchText(TestResult r) {
        StringBuilder s = new StringBuilder();
        for (var mod : r.testCase().mods()) {
            s.append(mod.slug()).append(' ').append(mod.name()).append(' ');
        }
        for (var issue : r.issues()) {
            s.append(issue.tag()).append(' ').append(issue.description()).append(' ');
        }
        return s.toString().toLowerCase();
    }

    private String fmtDownloads(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.0fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CSS — dark theme, full analytics layout
    // ══════════════════════════════════════════════════════════════════════════

    private static final String CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:#0a0c14;color:#e2e8f0;font-size:13px;line-height:1.5}
a{color:#818cf8;text-decoration:none}a:hover{text-decoration:underline}
code{font-family:ui-monospace,monospace;font-size:11px;background:#1e2235;padding:1px 4px;border-radius:3px}

/* Header */
header{background:#111320;border-bottom:1px solid #1e2235;padding:18px 28px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px}
.hdr-title{font-size:17px;font-weight:700;color:#a78bfa;display:flex;align-items:center;gap:8px}
.logo{font-size:22px}
.hdr-meta{color:#4a5568;font-size:12px}

/* KPI bar */
.kpi-bar{display:flex;flex-wrap:wrap;gap:10px;padding:16px 28px;background:#0d0f1c;border-bottom:1px solid #1e2235}
.kpi{background:#111320;border:1px solid #1e2235;border-radius:8px;padding:10px 16px;min-width:100px}
.kpi-lbl{font-size:10px;text-transform:uppercase;letter-spacing:.06em;color:#4a5568}
.kpi-val{font-size:26px;font-weight:800;margin-top:2px}
.kpi-val.blue{color:#60a5fa}.kpi-val.green{color:#4ade80}.kpi-val.sky{color:#38bdf8}
.kpi-val.yellow{color:#facc15}.kpi-val.red{color:#f87171}.kpi-val.purple{color:#c084fc}

/* Charts row */
.charts-row{display:flex;flex-wrap:wrap;gap:12px;padding:16px 28px;background:#0d0f1c;border-bottom:1px solid #1e2235}
.chart-card{background:#111320;border:1px solid #1e2235;border-radius:8px;padding:14px 16px;display:flex;flex-direction:column;align-items:flex-start;gap:10px}
.chart-title{font-size:12px;font-weight:600;color:#94a3b8;text-transform:uppercase;letter-spacing:.05em}
.legend{display:flex;flex-direction:column;gap:4px;font-size:11px}
.leg-item{display:flex;align-items:center;gap:6px}
.leg-dot{width:10px;height:10px;border-radius:2px;display:inline-block}

/* Metrics row */
.metrics-row{display:flex;flex-wrap:wrap;gap:12px;padding:16px 28px;background:#0d0f1c;border-bottom:1px solid #1e2235}
.metrics-card{background:#111320;border:1px solid #1e2235;border-radius:8px;padding:14px 16px;min-width:200px;flex:1}
.metrics-card.wide{flex:2;min-width:360px}
.inner-tbl{width:100%;border-collapse:collapse;font-size:12px;margin-top:8px}
.inner-tbl th{color:#64748b;padding:5px 8px;text-align:left;border-bottom:1px solid #1e2235;font-weight:600}
.inner-tbl td{padding:5px 8px;border-bottom:1px solid #111320;vertical-align:middle}
.inner-tbl tr:last-child td{border-bottom:none}
.mono{font-family:ui-monospace,monospace;font-size:11px}
.rate-good{color:#4ade80}.rate-warn{color:#facc15}.rate-bad{color:#f87171}

/* Controls */
.controls{display:flex;flex-wrap:wrap;gap:10px;align-items:center;padding:12px 28px;background:#0d0f1c;border-bottom:1px solid #1e2235;position:sticky;top:0;z-index:10}
input[type=text]{background:#111320;border:1px solid #2d3148;border-radius:6px;color:#e2e8f0;padding:6px 11px;font-size:12px;width:240px;outline:none}
input[type=text]:focus{border-color:#6366f1}
select{background:#111320;border:1px solid #2d3148;border-radius:6px;color:#e2e8f0;padding:6px 11px;font-size:12px;outline:none}
button{background:#6366f1;border:none;border-radius:6px;color:#fff;cursor:pointer;font-size:12px;padding:6px 14px;font-weight:600}
button:hover{background:#4f46e5}
.row-count{color:#4a5568;font-size:11px;margin-left:auto}

/* Results table */
.tbl-wrap{padding:16px 28px;overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:12px}
th{background:#111320;padding:9px 10px;text-align:left;font-weight:600;color:#64748b;cursor:pointer;white-space:nowrap;user-select:none;border-bottom:1px solid #1e2235;position:sticky;top:45px;z-index:5}
th:hover{color:#e2e8f0}
th.sorted-asc::after{content:" ▲";color:#6366f1}
th.sorted-desc::after{content:" ▼";color:#6366f1}
td{padding:8px 10px;border-bottom:1px solid #0d0f1c;vertical-align:top}
tr:hover td{background:#111320}

/* Badges */
.badge{display:inline-block;border-radius:4px;padding:2px 7px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.04em;white-space:nowrap}
.badge.pass{background:#14532d;color:#4ade80}
.badge.bridged{background:#1e3a5f;color:#60a5fa}
.badge.perf{background:#3b2a00;color:#facc15}
.badge.timeout{background:#3b1f00;color:#fb923c}
.badge.crash{background:#3b0000;color:#f87171}
.badge.mixin{background:#2d0052;color:#c084fc}
.badge.dependency{background:#1a1a00;color:#d4d400}
.badge.capability{background:#1a0033;color:#e879f9}
.badge.other{background:#1e2235;color:#94a3b8}

.loader-badge{font-size:10px;background:#1e2235;border:1px solid #2d3148;border-radius:3px;padding:1px 5px;color:#64748b;font-family:ui-monospace,monospace}
.mod-name{font-weight:600;color:#e2e8f0}
.mr-link{font-size:10px;color:#6366f1}
.dim{color:#4a5568;font-size:11px}
.issue{font-size:11px;padding:1px 0}
.iss-warn{color:#facc15}.iss-error{color:#fb923c}.iss-fatal{color:#f87171}
.log-toggle{cursor:pointer;color:#6366f1;font-size:10px;margin-top:4px;display:inline-block}
.log-box{display:none;margin-top:6px;background:#060810;border:1px solid #1e2235;border-radius:4px;padding:8px;font-family:ui-monospace,monospace;font-size:10px;white-space:pre-wrap;word-break:break-all;max-height:280px;overflow-y:auto;color:#64748b;tab-size:2}
.no-results{text-align:center;padding:40px;color:#4a5568}
footer{padding:14px 28px;color:#2d3148;font-size:11px;border-top:1px solid #1e2235;text-align:center}
""";
}
