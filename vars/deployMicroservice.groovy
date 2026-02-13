def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            jdk config.jdk ?: 'jdk'
            maven config.maven ?: 'mvn'
        }

        stages {

            stage('Prepare') {
                steps {
                    script {
                        // compute dynamic values
                        env.IMAGE_NAME = config.imageName ?: 'kuunyangna/myapp'
                        env.NAMESPACE  = config.namespace ?: 'default'
                        env.RELEASE    = config.helmRelease ?: env.IMAGE_NAME
                        env.BRANCH     = config.branch ?: 'main'
                    }
                }
            }

            stage('Checkout') {
                steps {
                    git branch: env.BRANCH, url: config.repoUrl
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
