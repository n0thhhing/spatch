package spatch.manager;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The nightly workflow's newest run on GitHub Actions, read from the public API: shown while it
 * builds, and when it failed, so a missing nightly has a reason. Progress is the share of steps
 * done across its jobs; a job that hasn't started counts as none, a finished or skipped one as all.
 */
final class Builds {
    private static final String RUNS = "https://api.github.com/repos/" + Releases.REPO + "/actions/workflows/nightly.yml/runs?per_page=1";

    static final class Run {
        final String title, commit, url, step;
        final boolean active, failed;
        final float progress;
        final long started; // epoch millis, 0 when unknown

        Run(String title, String commit, String url, String step, boolean active, boolean failed, float progress, long started) {
            this.title = title;
            this.commit = commit;
            this.url = url;
            this.step = step;
            this.active = active;
            this.failed = failed;
            this.progress = progress;
            this.started = started;
        }
    }

    /** The newest run when it is building or failed, else null. Two requests while building, one otherwise. */
    static Run newest() throws IOException {
        try {
            JSONArray runs = new JSONObject(Releases.get(RUNS)).getJSONArray("workflow_runs");
            if (runs.length() == 0) return null;
            JSONObject run = runs.getJSONObject(0);
            boolean active = !"completed".equals(run.optString("status"));
            boolean failed = !active && "failure".equals(run.optString("conclusion"));
            if (!active && !failed) return null;
            String step = failed ? "Failed" : "Queued";
            float progress = 0;
            if (active) {
                JSONArray jobs = new JSONObject(Releases.get(run.getString("jobs_url"))).getJSONArray("jobs");
                float sum = 0;
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject job = jobs.getJSONObject(i);
                    if ("completed".equals(job.optString("status"))) {
                        sum += 1;
                        continue;
                    }
                    JSONArray steps = job.optJSONArray("steps");
                    if (steps == null || steps.length() == 0) continue;
                    int done = 0;
                    for (int s = 0; s < steps.length(); s++) {
                        JSONObject st = steps.getJSONObject(s);
                        if ("completed".equals(st.optString("status"))) done++;
                        else if ("in_progress".equals(st.optString("status")) && step.equals("Queued")) step = job.optString("name") + ": " + st.optString("name");
                    }
                    sum += done / (float) steps.length();
                }
                progress = jobs.length() == 0 ? 0 : sum / jobs.length();
            }
            String sha = run.optString("head_sha", "");
            return new Run(run.optString("display_title", "Nightly"), sha.length() >= 7 ? sha.substring(0, 7) : sha,
                run.optString("html_url", Releases.PAGE), step, active, failed, progress, time(run.optString("run_started_at", "")));
        } catch (JSONException e) {
            throw new IOException("unexpected Actions reply", e);
        }
    }

    private static long time(String iso) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }
}
