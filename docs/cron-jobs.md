# MicahPod Cron Jobs

MicahPod relies on scheduled jobs for feed updates and digests.

## Current Jobs

| Job | Frequency | Model | Purpose |
| --- | --- | --- | --- |
| Feed Refresh | Hourly | Deepseek | Refresh podcast feeds and episode lists |
| Daily Digest (AM/PM) | 10:00 AM and 10:00 PM | Deepseek | Generate recommendation digests |

## Procedure: Validate Feed Refresh

1. Identify a podcast with a recent episode.
2. Wait for the top of the hour.
3. After the job runs, confirm the episode appears.
4. If it does not appear, manually refresh in the UI and check logs.

## Procedure: Validate Digests

1. Open the recommendations or digest view in the UI.
2. Confirm the content changes after 10 AM or 10 PM.
3. If it does not update, check the cron configuration and database connectivity.
