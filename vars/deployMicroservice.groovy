// vars/deployMicroservice.groovy
def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            jdk config.jdk ?: 'jdk17'
            maven config.maven ?: 'mvn'
        }

        // Use string interpolation to make environment block valid
        environment {
            IMAGE_NAME = "${config.imageName ?: 'default-image'}"
            NAMESPACE  = "${config.namespace ?: 'default'}"
            RELEASE    = "${config.helmRelease ?: config.imageName ?: 'default-release'}"
            BRANCH     = "${config.branch ?: 'main'}"
        }

        stages {

            stage('Checkout') {
                steps {
                    git branch: "${BRANCH}", url: "${config.repoUrl ?: error('repoUrl must be provided')}"
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
                        credentialsId: "${config.dockerCreds ?: 'docker-cred'}",
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                            echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                            docker push ${IMAGE_NAME}:${env.BUILD_NUMBER}
                        """
                    }
                }
            }

            stage('Deploy with Helm') {
                steps {
                    sh """
                        helm upgrade --install ${RELEASE} ${config.helmChart ?: './helm'} \
                          --namespace ${NAMESPACE} \
                          --set image.repository=${IMAGE_NAME} \
                          --set image.tag=${env.BUILD_NUMBER}
                    """
                }
            }
        }

        post {
            success {
                echo "Pipeline completed successfully for ${IMAGE_NAME}!"
            }
            failure {
                echo "Pipeline failed for ${IMAGE_NAME}. Check logs!"
            }
        }
    }
}
