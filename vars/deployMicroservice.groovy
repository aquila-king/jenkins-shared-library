// vars/deployMicroservice.groovy
def call(Map config = [:]) {

    pipeline {
        agent any

        // Tools: use configured names or fallback to known defaults
        tools {
            jdk config.jdk ?: 'jdk'      
            maven config.maven ?: 'mvn'   
        }

        stages {

            stage('Prepare Environment') {
                steps {
                    script {
                        // Assign dynamic environment variables safely
                        env.IMAGE_NAME = config.imageName ?: 'kuunyangna/myapp'
                        env.NAMESPACE  = config.namespace ?: 'default'
                        env.RELEASE    = config.helmRelease ?: env.IMAGE_NAME
                        env.BRANCH     = config.branch ?: 'main'
                        env.DOCKER_CREDS = config.dockerCreds ?: 'docker-cred'
                        env.HELM_CHART = config.helmChart ?: './helm-chart'
                        env.REPO_URL   = config.repoUrl ?: error("repoUrl must be provided in config")
                    }
                }
            }

            stage('Checkout') {
                steps {
                    git branch: env.BRANCH, url: env.REPO_URL
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
                    sh "docker build -t ${env.IMAGE_NAME}:${env.BUILD_NUMBER} ."
                }
            }

            stage('Docker Push') {
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: env.DOCKER_CREDS,
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
        withCredentials([[
            $class: 'AmazonWebServicesCredentialsBinding',
            credentialsId: 'aws-cred'
        ]]) {
            withEnv(["PATH+TOOLS=/usr/local/bin"]) {
                sh """
                    set -e

                    aws sts get-caller-identity
                    aws eks update-kubeconfig --region us-east-2 --name aquila-cluster
                    kubectl get nodes

                    helm upgrade --install ${env.RELEASE} ${env.HELM_CHART} \
                      --namespace ${env.NAMESPACE} \
                      --create-namespace \
                      --set image.repository=${env.IMAGE_NAME} \
                      --set image.tag=${env.BUILD_NUMBER}

                    kubectl rollout status deployment/${env.RELEASE} -n ${env.NAMESPACE}
                """
            }
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
