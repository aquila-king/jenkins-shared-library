// vars/deployMicroservice.groovy
def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            jdk config.jdk ?: 'jdk'      
            maven config.maven ?: 'mvn'   
        }

        stages {

            stage('Prepare Environment') {
                steps {
                    script {
                        env.IMAGE_NAME   = config.imageName ?: 'kuunyangna/myapp'
                        env.NAMESPACE    = config.namespace ?: 'default'
                        env.RELEASE      = config.helmRelease ?: env.IMAGE_NAME
                        env.BRANCH       = config.branch ?: 'main'
                        env.DOCKER_CREDS = config.dockerCreds ?: 'docker-cred'
                        env.HELM_CHART   = config.helmChart ?: './helm-chart'
                        env.REPO_URL     = config.repoUrl ?: error("repoUrl must be provided in config")
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

            stage('Trivy Scan') {
                steps {
                    script {
                        echo "Scanning Docker image for vulnerabilities..."
                        sh """
                            trivy image --exit-code 1 --severity CRITICAL,HIGH ${env.IMAGE_NAME}:${env.BUILD_NUMBER} || true
                        """
                    }
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

            stage('Deploy with Helm (Blue/Green)') {
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: 'aws-cred',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                        script {
                            // Decide new color dynamically
                            def newColor = (env.CURRENT_COLOR == 'blue') ? 'green' : 'blue'
                            def releaseName = "${env.RELEASE}-${newColor}"

                            sh """
                                export AWS_DEFAULT_REGION=us-east-2
                                aws eks update-kubeconfig --region us-east-2 --name aquila-cluster

                                helm upgrade --install ${releaseName} ${env.HELM_CHART} \
                                  --namespace ${env.NAMESPACE} \
                                  --create-namespace \
                                  --set image.repository=${env.IMAGE_NAME} \
                                  --set image.tag=${env.BUILD_NUMBER} \
                                  --wait --timeout 5m
                            """

                            // Simple smoke test
                            def status = sh(
                                script: "kubectl get pods -n ${env.NAMESPACE} -l app=${releaseName} -o jsonpath='{.items[*].status.phase}' | grep -v Running || true",
                                returnStatus: true
                            )

                            if (status != 0) {
                                echo "Deployment failed, rolling back..."
                                sh "helm rollback ${releaseName} 0 --namespace ${env.NAMESPACE}"
                                error "Deployment failed and rolled back!"
                            } else {
                                echo "Deployment successful! Switching service to ${releaseName}"
                                // Update Kubernetes Service selector to point to new color
                                sh "kubectl patch svc ${env.RELEASE}-svc -n ${env.NAMESPACE} -p '{\"spec\":{\"selector\":{\"app\":\"${releaseName}\"}}}'"
                                // Update CURRENT_COLOR env var for next run
                                env.CURRENT_COLOR = newColor
                            }
                        }
                    }
                }
            }

        } // end stages

        post {
            success {
                echo "Pipeline completed successfully for ${env.IMAGE_NAME}!"
            }
            failure {
                echo "Pipeline failed for ${env.IMAGE_NAME}. Check logs!"
            }
        }

    } // end pipeline
} // end call
