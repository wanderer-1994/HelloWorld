pipeline {
    agent any
    stages {
        stage('build') {
            steps {
                bat 'npm --version'
                bat 'npm install'
                bat 'node script'
            }
        }
    }
}