def call(Map config) {
    // config map expects:
    // repoUrl, imageName, namespace, tag (optional, default 'v1')

    def tag = config.tag ?: "v1"
    def fullImage = "kuunyangna/${config.imageName}:${tag}"

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
            stage('Checkout') {
                steps {
                    git branch: 'main', url: config.repoUrl
                }
            }

            stage('Build & Test') {
                steps {
                    sh 'mvn clean package || true'
                }
            }

            stage('Build Docker Image') {
                steps {
                    sh "docker build -t ${fullImage} ."
                }
            }

            stage('Push Docker Image') {
                steps {
                    withEnv(["DOCKER_USERNAME=${DOCKER_CREDS_USR}", "DOCKER_PASSWORD=${DOCKER_CREDS_PSW}"]) {
                        sh """
                            echo \$DOCKER_PASSWORD | docker login -u \$DOCKER_USERNAME --password-stdin
                            docker push ${fullImage}
                        """
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
                            aws eks update-kubeconfig --region us-east-2 --name aquila-cluster

                            # Ensure namespace exists
                            kubectl get ns ${config.namespace} || kubectl create ns ${config.namespace}

                            # Apply deployment YAML
                            kubectl apply -f k8-deployment.yaml -n ${config.namespace}

                            # Apply service YAML
                            kubectl apply -f k8-service.yaml -n ${config.namespace}

                            # Update deployment image
                            kubectl set image deployment/${config.imageName}-deployment ${config.imageName}-container=${fullImage} -n ${config.namespace}

                            # Wait for rollout to finish
                            kubectl rollout status deployment/${config.imageName}-deployment -n ${config.namespace}
                        """
                    }
                }
            }
        }
    }
}
