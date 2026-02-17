// vars/deployMicroservice.groovy
def call(Map config) {
    if (!config.repoUrl || !config.imageName || !config.namespace) {
        error "Please provide repoUrl, imageName, and namespace"
    }

    // Generate unique image tag using build number
    def imageTag = "v${env.BUILD_NUMBER}"
    def fullImage = "kuunyangna/${config.imageName}:${imageTag}"

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
                steps { cleanWs() }
            }

            stage('Checkout Code') {
                steps { git branch: 'main', url: config.repoUrl }
            }

            stage('Unit Testing') {
                steps { sh 'mvn test' }
            }

            stage('Build Docker Image') {
                steps { sh "docker build -t ${fullImage} ." }
            }

            stage('Push Docker Image') {
                steps {
                    sh """
                        echo \$DOCKER_CREDS_PSW | docker login -u \$DOCKER_CREDS_USR --password-stdin
                        docker push ${fullImage}
                    """
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
                            kubectl set image deployment/${config.imageName}-deployment ${config.imageName}-container=${fullImage} -n ${config.namespace} --record
                            kubectl rollout status deployment/${config.imageName}-deployment -n ${config.namespace}
                        """
                    }
                }
            }
        }
    }
}
