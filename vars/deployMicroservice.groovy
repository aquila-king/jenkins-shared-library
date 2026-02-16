def call(Map config) {
    pipeline {
        agent any

        tools {
            maven 'mvn'
            jdk 'jdk'
        }

        environment {
            DOCKER_CREDS = credentials('docker-cred')
        }

        stages {
            stage('Cleanup') {
                steps {
                    cleanWs()
                }
            }

            stage('Checkout Code') {
                steps {
                    git branch: 'main', url: "${config.repoUrl}"
                }
            }

            stage('Unit Testing') {
                steps {
                    sh 'mvn test'
                }
            }

            stage('Build Docker Image') {
                steps {
                    sh "docker build -t kuunyangna/${config.imageName}:v2 ."
                }
            }

            stage('Push Docker Image') {
                steps {
                    withDockerRegistry([credentialsId: 'docker-cred', url: 'https://index.docker.io/v1/']) {
                        sh "docker push kuunyangna/${config.imageName}:v2"
                    }
                }
            }

            stage('Deploy to EKS') {
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: 'aws-cred',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                        sh """
                            # Update kubeconfig to connect Jenkins to your EKS cluster
                            aws eks update-kubeconfig --region us-east-2 --name ${config.clusterName}

                            # Apply Kubernetes manifests
                            kubectl apply -f k8-deployment.yaml
                            kubectl apply -f k8-service.yaml

                            # Update deployment to new Docker image
                            kubectl set image deployment/myapp-deployment myapp-container=kuunyangna/${config.imageName}:v2 --record

                            # Wait for rollout to complete
                            kubectl rollout status deployment/myapp-deployment
                        """
                    }
                }
            }
        }
    }
}
