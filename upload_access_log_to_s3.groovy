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
