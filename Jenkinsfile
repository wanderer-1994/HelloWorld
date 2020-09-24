def build(){
    stage("build"){
        def action = "install"
        def run = "script"
        bat "npm ${action}"
        bat "node ${run}"
    }
}

node {
    stage("copy"){
        bat 'npm --version'
        bat 'git clone https://github.com/wanderer-1994/HelloWorld.git'
        bat 'xcopy HelloWorld\\* * /E/H'
        bat 'rmdir /Q/S HelloWorld'
    }
    build()
}