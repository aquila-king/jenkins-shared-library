// deployMicroservice.groovy
// Usage in Jenkinsfile: deployMicroservice(namespace: 'auth', imageName: 'auth-service', repoUrl: 'https://github.com/...')

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
                    sh '''
                        echo $DOCKER_CREDS_PSW | docker login -u $DOCKER_CREDS_USR --password-stdin
                        docker push kuunyangna/${config.imageName}:v2
                    '''
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
                            # Update kubeconfig to connect Jenkins to EKS
                            aws eks update-kubeconfig --region us-east-2 --name aquila-cluster

                            # Apply repo-specific Kubernetes manifests
                            cd \$WORKSPACE
                            kubectl apply -f k8-deployment.yaml -n ${config.namespace}
                            kubectl apply -f k8-service.yaml -n ${config.namespace}

                            # Update deployment to new image
                            kubectl set image deployment/${config.imageName}-deployment \
                                ${config.imageName}-container=kuunyangna/${config.imageName}:v2 \
                                -n ${config.namespace} --record

                            # Wait for rollout
                            kubectl rollout status deployment/${config.imageName}-deployment -n ${config.namespace}
                        """
                    }
                }
            }
        }
    }
}
