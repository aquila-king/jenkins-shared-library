def call(Map config = [:]) {

    pipeline {
        agent any

        // Tools configured in Jenkins
        tools {
            jdk config.jdk ?: 'jdk'      // use your installed JDK tool name
            maven config.maven ?: 'mvn'  // use your installed Maven tool name
        }

        // Environment variables
        environment {
            IMAGE_NAME = config.imageName ?: 'kuunyangna/myapp'
            NAMESPACE  = config.namespace ?: 'default'
            RELEASE    = config.helmRelease ?: "${config.imageName ?: 'myapp'}"
            BRANCH     = config.branch ?: 'main'
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
                        docker build -t ${env.IMAGE_NAME}:${env.BUILD_NUMBER} .
                    """
                }
            }

            stage('Docker Push') {
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: config.dockerCreds ?: 'docker-cred',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                            echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                            docker push ${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                        """
                    }
                }
            }

            stage('Deploy with Helm') {
                steps {
                    sh """
                        helm upgrade --install ${env.RELEASE} ${config.helmChart} \
                          --namespace ${env.NAMESPACE} \
                          --set image.repository=${env.IMAGE_NAME} \
                          --set image.tag=${env.BUILD_NUMBER}
                    """
                }
            }
        }

        post {
            success {
                echo "Pipeline completed successfully for ${env.IMAGE_NAME}!"
            }
            failure {
                echo "Pipeline failed for ${env.IMAGE_NAME}. Check logs!"
            }
        }
    }
}
