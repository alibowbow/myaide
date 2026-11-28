package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Work browser's durable fences, kept under one key so that a job's check and its reservation are one write on one queue
 * (BrowserEngine's WORK_STORAGE_IO): for each Work job, the one step whose result is not known (its receipt row: when, on which
 * site, what it did, for what request). A job's fence is its own: an uncertain step of one Work job does not stop another
 * job's clicks and typing (준: needless limits off). The same job is stopped by it until the user checks the site, since that
 * step may have run and must not run twice. Rows are checked by the caller; this class only keeps them.
 */
public final class WorkFence {
    public enum Step { RESERVE, WAIT, BLOCKED }
    /** What a job's next step may do: reserve its own fence, wait for its own step in flight, or stop at its earlier one. */
    public record Decision(Step step, JSONObject earlier) {}
    private final Map<String, JSONObject> rows = new LinkedHashMap<>();
    private WorkFence() {}

    /** "" is no fence; one row is the record of an older version (one job's); else {"version":2,"jobs":{job:row}}. */
    public static WorkFence read(String saved) throws JSONException {
        WorkFence fence = new WorkFence();
        if (saved == null) throw new JSONException("Unreadable Work fence");
        if (saved.isBlank()) return fence;
        JSONObject value = new JSONObject(saved);
        if (value.optInt("version") == 2) {
            JSONObject jobs = value.getJSONObject("jobs");
            // Android's org.json has keys(), not keySet().
            for (java.util.Iterator<String> names = jobs.keys(); names.hasNext(); ) {
                String job = names.next();
                JSONObject row = jobs.getJSONObject(job);
                if (!job.equals(row.optString("job_id"))) throw new JSONException("Work fence row under another job");
                fence.rows.put(job, row);
            }
        } else fence.rows.put(value.getString("job_id"), value);
        return fence;
    }
    public List<JSONObject> rows() { return new ArrayList<>(rows.values()); }
    public JSONObject job(String job) { return rows.get(job); }
    /** The row of this receipt, or null. */
    public JSONObject receipt(String id) {
        for (JSONObject row : rows.values()) if (row.optString("receipt_id").equals(id)) return row;
        return null;
    }
    /** The latest row (by its time), or null. */
    public JSONObject newest() {
        JSONObject newest = null;
        for (JSONObject row : rows.values()) if (newest == null || row.optLong("at") >= newest.optLong("at")) newest = row;
        return newest;
    }
    public void put(JSONObject row) { rows.put(row.optString("job_id"), row); }
    public boolean remove(String job) { return rows.remove(job) != null; }
    public boolean removeReceipt(String id) {
        JSONObject row = receipt(id);
        return row != null && rows.remove(row.optString("job_id")) != null;
    }
    public String save() {
        if (rows.isEmpty()) return "";
        try {
            JSONObject jobs = new JSONObject();
            for (Map.Entry<String, JSONObject> row : rows.entrySet()) jobs.put(row.getKey(), row.getValue());
            return new JSONObject().put("version", 2).put("jobs", jobs).toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * The next step of this job, judged by this job's own row only: other jobs' rows never stop it. A row from before steps
     * were tiered (they fenced every click, links and file buttons included) that no running job holds is released first.
     */
    public Decision reserve(String job, Set<String> active) {
        rows.values().removeIf(row -> !row.has("tier") && !active.contains(row.optString("receipt_id")));
        JSONObject earlier = rows.get(job);
        if (earlier == null) return new Decision(Step.RESERVE, null);
        return new Decision(active.contains(earlier.optString("receipt_id")) ? Step.WAIT : Step.BLOCKED, earlier);
    }
}
