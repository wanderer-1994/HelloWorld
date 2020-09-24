node {
    stage("clone ext code"){
        git url:'git@bitbucket.org:adaptiveintegrationteam/aidevopsframework.git', branch: 'release/v3_3-ModifyCLapps' , credentialsId: 'JENKINSBB'
    }
    stage("copy"){
        bat 'npm --version'
        bat 'git clone https://github.com/wanderer-1994/HelloWorld.git'
        bat 'xcopy HelloWorld\\* * /E/H'
        bat 'rmdir /Q/S HelloWorld'
    }
    stage("build"){
        def action = "install"
        def run = "script"
        bat "npm ${action}"
        bat "node ${run}"
    }
}