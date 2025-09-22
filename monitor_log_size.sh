#!/bin/bash
# monitor_log_size.sh
# Usage: monitor_log_size.sh /path/to/logfile
# Example: /usr/local/bin/monitor_log_size.sh /var/log/httpd/access.log

set -euo pipefail

LOG_PATH="${1:-/var/log/httpd/access.log}"
THRESHOLD_BYTES=1073741824    # 1GB = 1024^3 = 1073741824 bytes
JENKINS_URL="http://jenkins-server:8080/"
JENKINS_JOB="upload-access-log"
JENKINS_USER="admin"             # Jenkins user
JENKINS_API_TOKEN="  API  "         # API token for the user
# If your Jenkins job needs parameters, we pass LOG_PATH parameter:
JENKINS_PARAMS="LOG_PATH=$(printf '%s' "$LOG_PATH" | jq -s -R -r @uri)"

# Optional: path to log file used by this script
SCRIPT_LOG="/var/log/monitor_log_size.log"

timestamp() { date +"%Y-%m-%d %H:%M:%S"; }

# ensure logfile exists
if [ ! -f "$LOG_PATH" ]; then
  echo "$(timestamp) ERROR: file not found: $LOG_PATH" | tee -a "$SCRIPT_LOG"
  exit 1
fi

# get file size in bytes
filesize=$(stat -c%s "$LOG_PATH")
echo "$(timestamp) INFO: $LOG_PATH size = $filesize bytes" | tee -a "$SCRIPT_LOG"

if [ "$filesize" -lt "$THRESHOLD_BYTES" ]; then
  echo "$(timestamp) INFO: size < 1GB, nothing to do." | tee -a "$SCRIPT_LOG"
  exit 0
fi

echo "$(timestamp) INFO: size >= 1GB. Triggering Jenkins job $JENKINS_JOB." | tee -a "$SCRIPT_LOG"

# Get crumb for CSRF (adjust URL if Jenkins uses basic auth over HTTPS)
crumb_json=$(curl -sS --user "${JENKINS_USER}:${JENKINS_API_TOKEN}" \
  "${JENKINS_URL}/crumbIssuer/api/json")

if [ -z "$crumb_json" ]; then
  echo "$(timestamp) ERROR: failed to get crumb from Jenkins. Response empty." | tee -a "$SCRIPT_LOG"
  exit 2
fi

crumb=$(echo "$crumb_json" | python3 -c "import sys, json; print(json.load(sys.stdin)['crumb'])")
crumb_field=$(echo "$crumb_json" | python3 -c "import sys, json; print(json.load(sys.stdin)['crumbRequestField'])")

# Trigger job with parameter LOG_PATH (URL encoded above)
trigger_url="${JENKINS_URL}/job/${JENKINS_JOB}/buildWithParameters?${JENKINS_PARAMS}"
response=$(curl -sS -o /dev/null -w "%{http_code}" --user "${JENKINS_USER}:${JENKINS_API_TOKEN}" \
  -H "${crumb_field}: ${crumb}" \
  -X POST "${trigger_url}")

if [ "$response" -ge 200 ] && [ "$response" -lt 300 ]; then
  echo "$(timestamp) INFO: Triggered Jenkins job successfully (HTTP $response)." | tee -a "$SCRIPT_LOG"
  exit 0
else
  echo "$(timestamp) ERROR: Failed to trigger Jenkins job. HTTP status: $response" | tee -a "$SCRIPT_LOG"
  exit 3
fi
