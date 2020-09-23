pipeline {
    agent any
    stages {
        stage('build') {
            steps {
                def message = ['Heool', 'vibrita']
                for (int i = 0; i < message.size(); ++i) {
                    echo 'testing with ${message[i]}'
                }
                bat 'npm --version'
                bat 'npm install'
                bat 'node script'
            }
        }
    }
}