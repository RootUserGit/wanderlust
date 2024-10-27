pipeline{
    agent any
    tools{
        jdk 'jdk17'
        nodejs 'node21'
    }
   parameters {
        string(name: 'Project_name', defaultValue: 'wanderlust', description: 'Project name')
    }
    environment {
        SCANNER_HOME=tool 'sonar-scanner'
        PROJECT_NAME = "${params.Project_name}"
        SONAR_TOKEN = credentials('jenkins')
    }
    stages {
        stage('Clean Workspace') {
            steps {
                cleanWs()
            }
        }        
        stage('Checkout from Git'){
            steps{
                git branch: 'main', url: 'https://github.com/RootUserGit/wanderlust.git'
            }
        }
        stage('Install Dependencies') {
            steps {
                sh "npm install"
            }
        }

                // Fetch and Update SonarQube Instance IP
        stage('Fetch and Update SonarQube IP') {
            steps {
                script {
                    // Fetch the instance public IP using curl
                    def publicIP = sh(script: "curl -s ifconfig.me -4", returnStdout: true).trim()
                    
                    // Update the SonarQube URL in the environment variable
                    env.SONAR_HOST_URL = "http://${publicIP}:9000"
                }
            }
        }
        stage("Sonarqube Analysis ") {
            steps {
                withSonarQubeEnv('sonar-server') {
                    sh ''' $SCANNER_HOME/bin/sonar-scanner \
                    -Dsonar.projectKey=wanderlust \
                    -Dsonar.sources=. \
                    -Dsonar.host.url=${SONAR_HOST_URL} \
                    -Dsonar.login=${SONAR_TOKEN} \
                    -X '''
                }
            }
        }

        // stage("Sonar Quality Gate Scan"){
        //     steps{
        //         timeout(time: 2, unit: "MINUTES"){
        //             waitForQualityGate abortPipeline: false
        //         }
        //     }
        // }
        stage('OWASP FS SCAN') {
            steps {
                    withCredentials([string(credentialsId: 'NVD_API_KEY', variable: 'NVD_API_KEY')]) {
                    dependencyCheck additionalArguments: "--scan ./ --disableYarnAudit --disableNodeAudit --nvdApiKey ${NVD_API_KEY}", odcInstallation: 'DP-Check'
                    dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
                }
            }
        }
        stage('TRIVY FS SCAN') {
            steps {
                sh "trivy fs --format  table -o trivy-fs-report.html ."
            }
        }
        // Fetch and Update the Ip of the instance 
        stage('Fetch and Update Instance IP') {
            steps {
                script {
                    // Fetch the instance public IP using curl
                    def publicIP = sh(script: "curl -s ifconfig.me -4", returnStdout: true).trim()
                    
                    // Debug output to confirm IP fetched correctly
                    echo "Fetched public IP: ${publicIP}"
                    
                    // Replace the IP in the frontend .env.sample file
                    sh """
                        sed -i 's|MONGODB_URI=.*|MONGODB_URI="mongodb://${publicIP}:27017/wanderlust"|' frontend/.env.sample
                        sed -i 's|REDIS_URL=.*|REDIS_URL="${publicIP}:6379"|' frontend/.env.sample
                    """
                    
                    // Replace the IP in the backend .env.sample file
                    sh """
                        sed -i 's|VITE_API_PATH=.*|VITE_API_PATH="http://${publicIP}:5000"|' backend/.env.sample
                    """
                    
                    // Optional: Output modified .env.sample files for debugging
                    sh "cat frontend/.env.sample"
                    sh "cat backend/.env.sample"
                }
            }
        }
          // Docker Compose Build Stage with Timeout
        stage('Docker-compose Build') {
            steps {
                script {
                    timeout(time: 5, unit: 'MINUTES') {
                        // Cleanup containers and images with dynamic names
                        sh "docker rm -f ${PROJECT_NAME}_mongo ${PROJECT_NAME}_frontend ${PROJECT_NAME}_backend ${PROJECT_NAME}_redis || true"
                        sh "docker rmi ${PROJECT_NAME}_backend ${PROJECT_NAME}_frontend --force || true"
                        
                        // Ensure all networks and containers are down
                        sh "docker-compose down --remove-orphans || true"

                        // Build and recreate containers using Docker Compose
                        // Set PROJECT_NAME in docker-compose commands
                        sh "PROJECT_NAME=${PROJECT_NAME} docker-compose build"
                        sh "PROJECT_NAME=${PROJECT_NAME} docker-compose -f docker-compose.yml up -d --force-recreate"
                    }
                }
            }
        }
        // stage('Docker Login') {
        //     steps {
        //         script {
        //             withCredentials([usernamePassword(credentialsId: 'docker', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
        //                 sh """
        //                 docker login -u $DOCKER_USERNAME -p $DOCKER_PASSWORD index.docker.io
        //                 """
        //             }
        //         }
        //     }
        // }
        stage('Docker-compose Push') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'docker', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
                        sh ''' echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin || exit 1 '''
                        // Tag and push backend and frontend images
                        sh "docker tag ${PROJECT_NAME}_backend rahulsinghpilkh/devpipeline-backend:latest"
                        sh "docker tag ${PROJECT_NAME}_frontend rahulsinghpilkh/devpipeline-frontend:latest"
                        
                        sh "docker push rahulsinghpilkh/devpipeline-backend:latest"
                        sh "docker push rahulsinghpilkh/devpipeline-frontend:latest"
                    }
                }
            }
        }
        stage("TRIVY"){
            steps{
                sh "trivy image rahulsinghpilkh/devpipeline-backend > trivy.json"
                sh "trivy image rahulsinghpilkh/devpipeline-frontend > trivy.json"
            }
        }
    }
}