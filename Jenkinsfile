node {
    stage("copy"){
        bat 'npm --version'
        bat 'git clone https://github.com/wanderer-1994/HelloWorld.git'
        bat 'copy HelloWorld\\script.js script.js'
        bat 'copy HelloWorld\\package.json package.json'
        bat 'copy HelloWorld\\package-lock.json package-lock.json'
        bat 'copy HelloWorld\\script2.js script2.js'
        bat 'rmdir /Q/S HelloWorld'
    }
    stage("build"){
        bat 'npm install'
        bat 'node script'
    }
}