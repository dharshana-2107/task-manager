// CI/CD pipeline for task-manager.
// GitHub push (webhook) -> Checkout -> Secret scan (Gitleaks) -> Maven build -> Tests -> Docker image
// -> Amazon ECR -> Ansible deploy to Amazon EKS -> Verify.
//
// AWS access comes from the IAM role attached to the Jenkins EC2 instance,
// so no AWS keys are stored in Jenkins or in this repo.

pipeline {
    agent any

    triggers {
        // Fired by the GitHub webhook on every push
        githubPush()
    }

    options {
        skipDefaultCheckout()          // checkout is its own visible stage below
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        AWS_REGION = 'ap-south-1'
        ECR_REPO   = 'task-manager'
        CLUSTER    = 'task-manager-cluster'
        NAMESPACE  = 'task-manager'
    }

    stages {
        stage('1. Checkout') {
            steps {
                checkout scm
                script {
                    env.VERSION    = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
                    env.ACCOUNT_ID = sh(script: 'aws sts get-caller-identity --query Account --output text', returnStdout: true).trim()
                    env.REGISTRY   = "${env.ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com"
                    env.IMAGE      = "${env.REGISTRY}/${env.ECR_REPO}:${env.VERSION}"
                    env.IMAGE_LATEST = "${env.REGISTRY}/${env.ECR_REPO}:latest"
                    env.KUBECONFIG = "${env.WORKSPACE}/.kubeconfig"
                }
                sh 'git log -1 --pretty=format:"Commit %h by %an: %s%n"'
            }
        }

        stage('2. Secret Scan (Gitleaks)') {
            steps {
                // Scans the full Git history for passwords, API keys and tokens.
                // Any finding fails the build before anything is built or deployed.
                sh '''
                    docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp \
                      -v "$WORKSPACE:/repo" zricethezav/gitleaks:v8.18.4 \
                      detect --source /repo --redact --verbose \
                      --report-format json --report-path /repo/gitleaks-report.json
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
                }
            }
        }

        stage('3. Build (Maven)') {
            steps {
                // Compile and package the JAR; tests run in their own stage next
                sh 'mvn -B clean package -DskipTests'
            }
        }

        stage('4. Test (JUnit)') {
            steps {
                // A failing test fails this stage, and every later stage is skipped
                sh 'mvn -B test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('5. Docker Build & Tag') {
            steps {
                // Packages the exact JAR that just passed the tests.
                // Tagged with the commit SHA (traceable) and "latest".
                sh '''
                    docker build -t "$IMAGE" .
                    docker tag "$IMAGE" "$IMAGE_LATEST"
                    docker images "$REGISTRY/$ECR_REPO"
                '''
            }
        }

        stage('6. Push to ECR') {
            steps {
                sh '''
                    aws ecr get-login-password --region "$AWS_REGION" \
                      | docker login --username AWS --password-stdin "$REGISTRY"
                    docker push "$IMAGE"
                    docker push "$IMAGE_LATEST"
                '''
            }
        }

        stage('7. Deploy to EKS (Ansible)') {
            steps {
                sh 'aws eks update-kubeconfig --name "$CLUSTER" --region "$AWS_REGION" --kubeconfig "$KUBECONFIG"'
                dir('ansible') {
                    sh '''
                        K8S_AUTH_KUBECONFIG="$KUBECONFIG" ansible-playbook -i inventory.ini deploy.yml \
                          -e "image=$IMAGE" \
                          -e "app_version=$VERSION"
                    '''
                }
            }
        }

        stage('8. Verify Deployment') {
            steps {
                sh '''
                    kubectl rollout status deployment/task-manager -n "$NAMESPACE" --timeout=180s
                    kubectl get deployment,pods,service -n "$NAMESPACE" -o wide
                '''
                dir('ansible') {
                    sh 'K8S_AUTH_KUBECONFIG="$KUBECONFIG" ansible-playbook -i inventory.ini verify.yml -e "app_version=$VERSION"'
                }
            }
        }
    }

    post {
        always {
            // Keep the Jenkins disk from filling up with old images
            sh 'docker image rm "$IMAGE" "$IMAGE_LATEST" >/dev/null 2>&1 || true'
        }
        success {
            echo "Version ${env.VERSION} is deployed and verified on EKS."
        }
        failure {
            echo 'Pipeline failed. Stages after the failure were skipped, so the live app was not changed by this run.'
        }
    }
}
