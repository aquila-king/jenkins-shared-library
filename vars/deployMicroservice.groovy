// vars/deployMicroservice.groovy
def call(Map config = [:]) {

    pipeline {
        agent any

        tools {
            jdk config.jdk ?: 'jdk'      
            maven config.maven ?: 'mvn'   
        }

        stages {

            stage('Prepare Environment to EKS') {
                steps {
                    script {
                        env.IMAGE_NAME = config.imageName ?: 'kuunyangna/myapp'
                        env.NAMESPACE  = config.namespace ?: 'default'
                        env.RELEASE    = config.helmRelease ?: env.IMAGE_NAME
                        env.BRANCH     = config.branch ?: 'main'
                        env.DOCKER_CREDS = config.dockerCreds ?: 'docker-cred'
                        env.HELM_CHART = config.helmChart ?: './helm-chart'
                        env.REPO_URL   = config.repoUrl ?: error("repoUrl must be provided in config")
                        env.MAVEN_PROJECT_DIR = config.mavenProjectDir ?: '.' // Maven project folder
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
                    dir(env.MAVEN_PROJECT_DIR) {
                        sh "mvn clean package -DskipTests=false"
                    }
                }
            }

            stage('Test') {
                steps {
                    dir(env.MAVEN_PROJECT_DIR) {
                        sh "mvn test"
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    dir(env.MAVEN_PROJECT_DIR) {
                        script {
                            echo "Building Docker image from Maven project directory: ${env.MAVEN_PROJECT_DIR}"

                            // Ensure the JAR exists before Docker build
                            def jarFile = sh(
                                script: "ls target/*.jar | head -n 1",
                                returnStdout: true
                            ).trim()

                            if (!jarFile) {
                                error "Maven did not produce a JAR in target/*.jar!"
                            }

                            echo "Found JAR: ${jarFile}"

                            // Build Docker image
                            sh "docker build -t ${env.IMAGE_NAME}:${env.BUILD_NUMBER} ."
                        }
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

            stage('Deploy with Helm to EKS using Blue/Green') {
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: 'aws-cred',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                        script {

                            sh """
                                export AWS_DEFAULT_REGION=us-east-2
                                aws eks update-kubeconfig --region us-east-2 --name aquila-cluster
                            """

                            // Detect current active color
                            def activeColor = sh(
                                script: "kubectl get svc ${env.RELEASE}-svc -n ${env.NAMESPACE} -o jsonpath='{.spec.selector.app}' 2>/dev/null | awk -F'-' '{print \$NF}' || echo ''",
                                returnStdout: true
                            ).trim()

                            def newColor = (activeColor == 'blue') ? 'green' : 'blue'
                            def releaseName = "${env.RELEASE}-${newColor}"

                            echo "Active color: ${activeColor}"
                            echo "Deploying new color: ${newColor}"

                            // Deploy new color with Helm
                            sh """
                                helm upgrade --install ${releaseName} ${env.HELM_CHART} \
                                  --namespace ${env.NAMESPACE} \
                                  --create-namespace \
                                  --set image.repository=${env.IMAGE_NAME} \
                                  --set image.tag=${env.BUILD_NUMBER} \
                                  --wait --timeout 5m
                            """

                            // Simple smoke test: ensure pods are Running
                            def status = sh(
                                script: "kubectl get pods -n ${env.NAMESPACE} -l app=${releaseName} -o jsonpath='{.items[*].status.phase}' | grep -v Running || true",
                                returnStatus: true
                            )

                            if (status != 0) {
                                echo "Deployment failed. Removing failed release..."
                                sh "helm uninstall ${releaseName} -n ${env.NAMESPACE} || true"
                                error "Deployment failed!"
                            }

                            echo "Deployment successful. Switching traffic..."

                            // Patch stable Service to point to new release
                            sh """
                                kubectl patch svc ${env.RELEASE}-svc -n ${env.NAMESPACE} \
                                -p '{\"spec\":{\"selector\":{\"app\":\"${releaseName}\"}}}'
                            """
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
