# Automated Jenkins Job Triggered by Access Log Size

---

# A small automation pipeline that:

1. Watches access.log size (monitor script).
2. When > threshold, triggers Jenkins job (REST API).
3. Jenkins job upload file to S3, verifies it, truncates original log safely.

# Purpose:

Webservers, reverse proxies and any system that produces very large logs and want automatic archival or offloading to S3 for retention, analytics, or else. 

Use Cases:

- Prevent disk fill-up from large access logs.
- Centralized archival for search /analytics.
- Compliance (keep raw logs in S3).
- Scheduled housekeeping for high-traffic sites.

# File

## monitor_log_size.sh

```bash
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
```

### Summary of Script Behavior

1. Checks if a log file exists.
2. Calculates its size.
3. If the file is ≥ 1GB, it:
    - Fetches a CSRF crumb from Jenkins.
    - Triggers a Jenkins job with the log file path as a parameter.
    - Logs all activity to `/var/log/monitor_log_size.log`.
4. Exits with appropriate status codes based on success or failure.

## upload_access_log_to_s3.groovy

```groovy
pipeline {
    agent any

    parameters {
        string(name: 'LOG_PATH', defaultValue: '/var/log/httpd/access.log', description: 'Path to access log to upload')
        string(name: 'S3_BUCKET', defaultValue: 'logs-2025y', description: 'S3 bucket name')
    }

    environment {
        AWS_CREDENTIALS_ID = 'b5968f81-c1e1-4fc7-9109-5e18bb88c4b6'  // Your Jenkins AWS credentials ID
        S3_REGION = 'us-east-1'
        TMP_UPLOAD_DIR = '/tmp/jenkins-log-upload'
        SHELL = '/bin/bash'   // Force Jenkins to use bash everywhere
    }

    options {
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Prepare') {
            steps {
                echo "Preparing to upload ${params.LOG_PATH} to s3://${params.S3_BUCKET}/"
                sh "mkdir -p ${TMP_UPLOAD_DIR}"
            }
        }

        stage('Check file exists & size') {
            steps {
                sh '''#!/bin/bash
                if [ ! -f "${LOG_PATH}" ]; then
                    echo "ERROR: file does not exist: ${LOG_PATH}" >&2
                    exit 2
                fi

                echo "File info:"
                ls -lh "${LOG_PATH}" || true
                stat -c '%n %s bytes' "${LOG_PATH}"
                '''
            }
        }

        stage('Upload to S3') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: "${env.AWS_CREDENTIALS_ID}"]]) {
                    sh '''#!/bin/bash
                    set -euo pipefail

                    export AWS_DEFAULT_REGION=${S3_REGION}
                    TIMESTAMP=$(date +%Y%m%dT%H%M%S)
                    HOSTNAME=$(hostname -s || echo unknown-host)
                    BASENAME=$(basename "${LOG_PATH}")
                    S3_KEY="logs/${HOSTNAME}/${BASENAME}.${TIMESTAMP}"

                    echo "Uploading ${LOG_PATH} to s3://${S3_BUCKET}/${S3_KEY}"
                    aws s3 cp "${LOG_PATH}" "s3://${S3_BUCKET}/${S3_KEY}" --only-show-errors

                    echo "${S3_KEY}" > ${TMP_UPLOAD_DIR}/s3_key.txt
                    '''
                }
            }
        }

        stage('Verify upload') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: "${env.AWS_CREDENTIALS_ID}"]]) {
                    sh '''#!/bin/bash
                    set -euo pipefail

                    export AWS_DEFAULT_REGION=${S3_REGION}
                    S3_KEY=$(cat ${TMP_UPLOAD_DIR}/s3_key.txt)

                    echo "Verifying s3://${S3_BUCKET}/${S3_KEY}"
                    aws s3api head-object --bucket "${S3_BUCKET}" --key "${S3_KEY}" >/dev/null
                    echo "Upload verified."
                    '''
                }
            }
        }

        stage('Truncate original file (safe)') {
            steps {
                sh '''#!/bin/bash
                set -euo pipefail

                FILE="${LOG_PATH}"
                if [ ! -f "$FILE" ]; then
                    echo "ERROR: file not found: $FILE" >&2
                    exit 4
                fi

                echo -n | sudo /usr/bin/tee "$FILE" > /dev/null
                echo "Truncated $FILE"
                '''
            }
        }

        stage('Post actions') {
            steps {
                echo "Done. Uploaded and truncated ${params.LOG_PATH}."
            }
        }
    }

    post {
        success {
            echo "SUCCESS: Log uploaded to S3"
        }
        failure {
            echo "FAILURE: check logs"
        }
    }
}
```

### Purpose:

This Jenkins pipeline automates the process of:

1. Uploading a specified log file (e.g. access log) to an AWS S3 bucket.
2. Verifying the upload.
3. Safely truncating the original log file to free space.
4. Logging the process at each step.

### Parameters:

- `LOG_PATH`: Path to the log file to upload (default: `/var/log/httpd/access.log`)
- `S3_BUCKET`: Target S3 bucket for uploads (default: `logs-2025y`)

### Environment Variables:

- `AWS_CREDENTIALS_ID`: Jenkins credentials ID for AWS access.
- `S3_REGION`: AWS region (`us-east-1`)
- `TMP_UPLOAD_DIR`: Temporary local directory for storing upload metadata.
- `SHELL`: Forces use of Bash for shell steps.
