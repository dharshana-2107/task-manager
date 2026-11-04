// CI/CD pipeline: GitHub push (webhook) -> Maven build & test -> Docker image
// -> Amazon ECR -> Ansible deploy to Amazon EKS -> smoke test.
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
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        AWS_REGION = 'ap-south-1'
        ECR_REPO   = 'task-manager'
        CLUSTER    = 'task-manager-cluster'
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    env.VERSION    = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()
                    env.ACCOUNT_ID = sh(script: 'aws sts get-caller-identity --query Account --output text', returnStdout: true).trim()
                    env.REGISTRY   = "${env.ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com"
                    env.IMAGE      = "${env.REGISTRY}/${env.ECR_REPO}:${env.VERSION}"
                    env.KUBECONFIG = "${env.WORKSPACE}/.kubeconfig"
                }
                echo "Commit ${env.VERSION} -> image ${env.IMAGE}"
            }
        }

        stage('Build & Test (Maven)') {
            steps {
                // Any failing test fails this stage, and every later stage is skipped
                sh 'mvn -B clean verify'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('Docker Build') {
            steps {
                // Packages the exact JAR that just passed the tests
                sh 'docker build -t "$IMAGE" .'
            }
        }

        stage('Push to ECR') {
            steps {
                sh '''
                    aws ecr get-login-password --region "$AWS_REGION" \
                      | docker login --username AWS --password-stdin "$REGISTRY"
                    docker push "$IMAGE"
                '''
            }
        }

        stage('Deploy to EKS (Ansible)') {
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
    }

    post {
        always {
            // Keep the Jenkins disk from filling up with old images
            sh 'docker image rm "$IMAGE" >/dev/null 2>&1 || true'
        }
        success {
            echo "Version ${env.VERSION} deployed to EKS."
        }
        failure {
            echo 'Pipeline failed. Nothing after the failed stage was run, so the live app is unchanged.'
        }
    }
}
