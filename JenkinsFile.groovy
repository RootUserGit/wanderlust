pipeline{
    agent any
    tools{
        jdk 'jdk 17'
        nodejs 'node21'
    }
    environment {
        SCANNER_HOME=tool 'sonar-scanner'
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
        stage("Sonarqube Analysis "){
            steps{
                withSonarQubeEnv('sonar-server') {
                    sh ''' $SCANNER_HOME/bin/sonar-scanner \
                    -Dsonar.projectKey=wanderlust \
                    -Dsonar.sources=. \
                    -Dsonar.host.url=http://34.224.212.190:9000 \
                    -Dsonar.login=squ_611099560cc699919a0b9a4ebfe035139188dc30'''
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
          // Docker Compose Build Stage with Timeout
        stage('Docker-compose Build') {
            steps {
                script {
                    timeout(time: 5, unit: 'MINUTES') { // Timeout set to 1 minute
                        // Remove images forcefully
                        sh 'docker rmi node mongo rahulsinghpilkh/devpipeline-frontend gpt-pipeline-frontend rahulsinghpilkh/devpipeline-backend gpt-pipeline-backend redis --force'
        
                        // Check if containers are running, then kill if they are
                        sh '''docker-compose down --remove-orphans'''
        
                        // Start containers with Docker Compose
                        sh 'node --version'
                        sh 'docker-compose -f docker-compose.yml up -d --force-recreate'
                    }
                }
            }
        }
        stage('Docker Login') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'docker', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
                        sh """
                        docker login -u $DOCKER_USERNAME -p $DOCKER_PASSWORD index.docker.io
                        """
                    }
                }
            }
        }
        stage('Docker-compose Push') {
            steps {
                script {
                        // Tag and push backend and frontend images
                        sh "docker tag gpt-pipeline-backend rahulsinghpilkh/devpipeline-backend:latest"
                        sh "docker tag gpt-pipeline-frontend rahulsinghpilkh/devpipeline-frontend:latest"
                        
                        sh "docker push rahulsinghpilkh/devpipeline-backend:latest"
                        sh "docker push rahulsinghpilkh/devpipeline-frontend:latest"
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