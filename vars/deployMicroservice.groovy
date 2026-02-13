def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            jdk config.jdk ?: 'jdk'
            maven config.maven ?: 'mvn'
        }

        environment {
            IMAGE_NAME = config.imageName
            NAMESPACE  = config.namespace ?: "default"
            RELEASE    = config.helmRelease ?: config.imageName
            BRANCH     = config.branch ?: "main"
        }

        stages {

            stage('Checkout') {
                steps {
                    git branch: BRANCH, url: config.repoUrl
                }
            }

            stage('Build') {
                steps {
                    sh "mvn clean package -DskipTests=false"
                }
            }

            stage('Test') {
                steps {
                    sh "mvn test"
                }
            }

            stage('Docker Build') {
                steps {
                    sh """
                        docker build -t ${IMAGE_NAME}:${env.BUILD_NUMBER} .
                    """
                }
            }

            stage('Docker Push') {
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: config.docker-Cred,
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                            echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin
                            docker push ${IMAGE_NAME}:${env.BUILD_NUMBER}
                        """
                    }
                }
            }

            stage('Deploy with Helm') {
                steps {
                    sh """
                        helm upgrade --install ${RELEASE} ${config.helmChart} \
                          --namespace ${NAMESPACE} \
                          --set image.repository=${IMAGE_NAME} \
                          --set image.tag=${env.BUILD_NUMBER}
                    """
                }
            }
        }
    }
}
