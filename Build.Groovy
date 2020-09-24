#!groovy
import groovy.sql.Sql
import groovy.json.JsonParserType
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import java.io.*
import java.net.*
import org.apache.commons.io.FileUtils
import java.util.*;
import groovy.json.JsonSlurper

echo "Build.Groovy file successfully loaded!!"

/**************************************************************
 * Global variables
 **************************************************************/
def userInput = null
def jobName = ""
def mvnHome = ""
def decsecurekey = ""
def repository = "libs-snapshot-local"
	artifactsCount = "6"	

if (userInput.equals("prod")) {
	repository = "libs-release-local"
	artifactsCount = "15"
}

countList = new ArrayList<String>();
munit_Build_Status = "SKIPPED"
munitSpanIndex = "0.00%"

lint_build_Status = "SKIPPED"
lintSpanIndex = "0.00%"

def buildProps = null
def deployProps = null

/**************************************************************
 * Functions
 **************************************************************/
def prevalidation() {
	// Capture values from build.properties
	buildProps = readProperties file: "./src/devops/Build.properties"
				
	// Capture values from environment specific properties file
	if (userInput.equals("dev")) {
		deployProps = readProperties file: "./src/devops/dev.properties"
	} else if(userInput.equals("sit")) {
		deployProps = readProperties file: "./src/devops/sit.properties"
	} else if(userInput.equals("uat")) {
		deployProps = readProperties file: "./src/devops/uat.properties"
	} else if(userInput.equals("qa")) {
		deployProps = readProperties file: "./src/devops/qa.properties"
	} else if(userInput.equals("reg")) {
		deployProps = readProperties file: "./src/devops/reg.properties"
	} else if(userInput.equals("prod")) {
		deployProps = readProperties file: "./src/devops/prod.properties"
	}
	
	// Set maven home path
	mvnHome = tool 'MVN_Home'
	
	/****************************************************************************
	* Validation 1: Check user has necessary permission in cloudhub environments
	*****************************************************************************/
	
	// Extract businessGroup entry form pom file, if missing then set to default business group.
	String pomfile = new File("${workspace}/pom.xml").getText('UTF-8')
	String businessGroup = "Unilever UK Limited"
	int startIndex = pomfile.indexOf("<businessGroup>")

	if (startIndex != -1) {
		startIndex = startIndex + 15
		int endIndex = pomfile.indexOf("</businessGroup>",startIndex)
		businessGroup = pomfile.substring(startIndex,endIndex)
	}
	
	// Extract logged in userID from environment variable
	wrap([$class: 'BuildUser']) {
		userID = env.BUILD_USER
	}
	
	// Extract cloudhub deployment environment name from properties file
	def envName = deployProps.Clenvname
	envName = envName.replace("\'","")
	
	// Call access validation module and based on the result allow/reject deployment.
	def clpermission = validateCloudhubUser(businessGroup,envName,userID)	
	// def clpermission = "success"
	
	if(!(clpermission.equals("success"))) {
		println "***** CloudHub User Access Validation *****\nStatus: FAILURE\nUserID: $userID\nBusinessGroup: $businessGroup\nEnvironment: $envName"
		error "User has insufficient privileges to deploy application in CloudHub environment"
	} else {
		println "***** CloudHub User Access Validation *****\nStatus: SUCCESS\nUserID: $userID\nBusinessGroup: $businessGroup\nEnvironment: $envName"
	}
	
	/**************************************************************
	* Validation 2: Check securekey in properties file and decrypt.
	***************************************************************/
	if (!deployProps.Securekey || deployProps.Securekey.equalsIgnoreCase("''")) {
		error "Empty/Missing securekey in properties file"
	} else {
		decsecurekey = sh (script: '#!/bin/sh\n' + "echo ${deployProps.Securekey} | openssl enc -aes-256-cbc -d -a -k ${skpassword}", returnStdout: true)
	}
}

/**
*method anypointCliModifyCLapps to perform objectstorev2/statiIp/Persistques set in anypoint Runtime
*/
def anypointCliModifyCLapps() {
	try
	{
	timeout(time: 3, unit: 'MINUTES') {	
	String pomfile = new File("${workspace}/pom.xml").getText('UTF-8')
	String businessGroup = "Unilever UK Limited"
	int startIndex = pomfile.indexOf("<businessGroup>")

	if (startIndex != -1) {
		startIndex = startIndex + 15
		int endIndex = pomfile.indexOf("</businessGroup>",startIndex)
		businessGroup = pomfile.substring(startIndex,endIndex)
	}
		def envProps = readProperties file: "artifactory/${userInput}.properties"
		pom = readMavenPom file: 'pom.xml'
		def envName = deployProps.Clenvname
		envName = envName.replace("\'","")
		withCredentials([usernamePassword(credentialsId: 'ANYPOINT-CLI', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
		sh """
		set +x
        if [[ "${businessGroup}" =~ ['!@#\$%^&*()_+'] ]]; then
        OrgbusinessGrp=`echo '$businessGroup' | awk -F '\\' '{print \$2}'`
        else
        OrgbusinessGrp=`echo '$businessGroup'`
        fi
		export HTTP_PROXY=http://awsduproxy.unilever.com:8080
		export ANYPOINT_HOST=anypoint.mulesoft.com
		export ANYPOINT_USERNAME="$USERNAME"
		export ANYPOINT_PASSWORD="$PASSWORD"
		export ANYPOINT_ORG="\${OrgbusinessGrp}"
		export ANYPOINT_ENV='$envName'
		CheckState=`anypoint-cli runtime-mgr cloudhub-application describe '${userInput}-${envProps.Clappname}' | grep 'Status' || true`
		if [[ "\${CheckState}" =~ "STARTED" || "\${CheckState}" =~ "UNDEPLOYED" || "\${CheckState}" =~ "DEPLOYING" ]]; then
		DeploymentState=`echo 'modify'`
		#echo "modify"
		else
		DeploymentState=`echo 'deploy'`
		#echo "deploy"
		fi
		if [[ "$envProps.Clworkertype" =~ "Micro" ]]; then
		Clworkertype=`echo "0.1"`
		fi
		if [[ "$envProps.Clworkertype" =~ "Small" ]]; then
		Clworkertype=`echo "0.2"`
		fi
		if [[ "$envProps.Clworkertype" =~ "Medium" ]]; then
		Clworkertype=`echo "1"`
		fi
		if [[ "$envProps.Clworkertype" =~ "Large" ]]; then
		Clworkertype=`echo "2"`
		fi
		if [[ "$envProps.Clworkertype" =~ "xLarge" ]]; then
		Clworkertype=`echo "4"`
		fi
		Region=`echo '$envProps.Clregion' | tr -d [:blank:]`
		echo "$userInput-$envProps.Clappname application is "\${DeploymentState}ing" to Cloud-Hub"
		anypoint-cli runtime-mgr cloudhub-application "\${DeploymentState}" --runtime '$envProps.Muleversion' --workers '$envProps.Clworkers' --workerSize "\${Clworkertype}" --region "\${Region}" --property 'anypoint.platform.config.analytics.agent.enabled:true' --property 'anypoint.platform.client_id:$envProps.ClClientID' --property 'anypoint.platform.client_secret:$envProps.ClClientSecret' --property 'secure.key:'$decsecurekey'' --property 'mule.env:$userInput' --objectStoreV1 '$envProps.ClObjectStorev1' --persistentQueues '$envProps.ClPersistentQueues' --staticIPsEnabled '$envProps.ClStaticIPsEnabled' --autoRestart '$envProps.ClautoRestart' '$userInput-$envProps.Clappname' '$env.WORKSPACE/artifactory/${pom.artifactId}-${pom.version}.zip'
		if [[ "\${CheckState}" =~ "UNDEPLOYED" ]]; then
		anypoint-cli runtime-mgr cloudhub-application start '$userInput-$envProps.Clappname'
		anypoint-cli runtime-mgr cloudhub-application tail-logs '$userInput-$envProps.Clappname'
		else
		anypoint-cli runtime-mgr cloudhub-application tail-logs '$userInput-$envProps.Clappname'
		fi
		#anypoint-cli runtime-mgr cloudhub-application modify '$userInput-$envProps.Clappname' --objectStoreV1 '$envProps.ClObjectStorev1' --persistentQueues '$envProps.ClPersistentQueues' --staticIPsEnabled '$envProps.ClStaticIPsEnabled' --autoRestart '$envProps.ClautoRestart' || true
        #RESULTORGID=`curl -x http://awsduproxy.unilever.com:8080  -H "client_id: ${anypoint_client_id}" -H "client_secret: ${anypoint_secret_id}"  https://anypoint-mulesoft-token-v2.ir-e1.cloudhub.io/accounts/api/profile |  grep -A 2 "\${OrgbusinessGrp}" | grep 'id' | cut -d '"' -f4 | head -1`
        #RESULTENVID=`curl -x http://awsduproxy.unilever.com:8080  -H "client_id: ${anypoint_client_id}" -H "client_secret: ${anypoint_secret_id}"  https://anypoint-mulesoft-token-v2.ir-e1.cloudhub.io/accounts/api/organizations/\${RESULTORGID}/environments | grep -B 1 '$envName' | cut -d '"' -f4 | head -1`
        #curl -x http://awsduproxy.unilever.com:8080 -X PUT -H "client_id: ${anypoint_client_id}" -H "client_secret: ${anypoint_secret_id}" https://anypoint-mulesoft-token-v2.ir-e1.cloudhub.io/cloudhub/api/v2/applications/'$userInput-$envProps.Clappname' -H 'Content-Type: application/json' -H "X-ANYPNT-ENV-ID: \${RESULTENVID}" -H "X-ANYPNT-ORG-ID: \${RESULTORGID}" -d '{"loggingCustomLog4JEnabled": "true"}'
        set -x
		"""
	}
	println("$userInput-$envProps.Clappname objectStoreV1 is set to $envProps.ClObjectStorev1");
	println("$userInput-$envProps.Clappname persistentQueues is set to $envProps.ClPersistentQueues");
	println("$userInput-$envProps.Clappname staticIPsEnabled is set to $envProps.ClStaticIPsEnabled");
	println("$userInput-$envProps.Clappname autoRestart is set to $envProps.ClautoRestart");
	}
}
	catch(err) {
	println("Cloudhub anypointCliModifyCLapps failed");
	throw err
	}
	}

def notifyBuild(String buildStatus = 'BUILD STARTED') {	

	// build status of null means successful
	buildStatus =  buildStatus ?: 'BUILD SUCCESSFUL'

	// Default values
	def colorName = 'RED'
	def colorCode = '#FF0000'
	def subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
	def summary = "${subject} (${env.BUILD_URL})"
	def details = """'${env.JOB_NAME} [${env.BUILD_NUMBER}]' - Check console output at ${env.BUILD_URL}"""

	// Override default values based on build status
	if (buildStatus == 'BUILD STARTED') {
	color = 'YELLOW'
	colorCode = '#FFFF00'
	} else if (buildStatus == 'BUILD SUCCESSFUL') {
	color = 'GREEN'
	colorCode = '#00FF00'
	} else {
	color = 'RED'
	colorCode = '#FF0000'
	}

	// Send notifications
	//slackSend (color: colorCode, message: summary)

	/*
	emailext (
	  subject: subject,
	  body: details,
	  recipientProviders: [[$class: 'DevelopersRecipientProvider']]
	)
	*/
}

def publishDashBoard() {
	def dashUrlEnv = "$BUILD_URL"
	def htmlFile = new File("${workspace}/Dashboard/quality_dash_index.html")
	String textInHtml = new File("${workspace}/Dashboard/quality_dash_index.html").getText('UTF-8')
	try {
		if(countList.size() == 1 || countList.size() == 0) {
			statusOfDep = "<h4 style=\"color:white;text-align:right\"><font size=\"5\"><b>Initiated</b></font>&nbsp&nbsp&nbsp<img src=\"dist/img/red_circle.png\" style=\"width:10%;height:10%;\"/></h4>"
			depStatus = textInHtml.replace("depStatus",statusOfDep).replaceAll("-dashUrl-",dashUrlEnv)
			htmlFile.write(depStatus)
		} else if(countList.size() == 2) {
			statusOfDep = "<h4 style=\"color:white;text-align:right\"><font size=\"5\"><b>Check Munit/Lint</b></font>&nbsp&nbsp&nbsp<img src=\"dist/img/amber_circle.png\" style=\"width:10%;height:10%;\"/></h4>"
			depStatus = textInHtml.replace("depStatus",statusOfDep).replaceAll("-dashUrl-",dashUrlEnv)
			htmlFile.write(depStatus)
		} else {
			statusOfDep = "<h4 style=\"color:white;text-align:right\"><font size=\"5\"><b>Deployed</b></font>&nbsp&nbsp&nbsp<img src=\"dist/img/green_circle.png\" style=\"width:10%;height:10%;\"/></h4>"
			depStatus = textInHtml.replace("depStatus",statusOfDep).replaceAll("-dashUrl-",dashUrlEnv)
			htmlFile.write(depStatus)
		}							

		publishHTML(target:[
			allowMissing: true, 
			alwaysLinkToLastBuild: true, 
			keepAll: true, 
			reportDir: '\\Dashboard\\', 
			reportFiles: 'quality_dash_index.html', 
			reportName: 'Adaptive Integration Quality Dashboard'
		])
	} catch(err) {
		println "Error during publishing dashboard"
	} 
}

/**
 * This method is used for building QualityDashboard
 * Parameters:
 *  reportPercent - for reflecting the dashboard status ( NO GO, GO ,WAIT)
 *  stringToReplace - displaying the status of each lint,munit,percent and 
 *  reportPercentString - containing the value to be replaced.
*/
def buildQualityDashboard(double reportPercent,String stringToReplace,String dashboardReportData,String reportPercentString){
	try {
		String finalReport = ""
		def htmlFile = new File("${workspace}/Dashboard/quality_dash_index.html")
		String textInHtml=""
		String colorAppender = "";
		
		if(htmlFile.exists()) {
			if(reportPercent >= 80) {
				colorAppender = " color=\"green\">"+reportPercentString
				countList.add("1");
			} else if(reportPercent > 1 && reportPercent < 80){
				colorAppender = " color=\"red\">"+reportPercentString
			} else {
				colorAppender = "No "+dashboardReportData+" Report"
			}
			
			String appHeader = deployProps.Clappname.replace("\'","")
			
			textInHtml = new File("${workspace}/Dashboard/quality_dash_index.html").getText('UTF-8')			
			if(dashboardReportData == "Performance"){
				finalReport = textInHtml.replace(">Performance-Report-Pending",colorAppender)
				htmlFile.write(finalReport)				
			} else{
				finalReport = textInHtml.replace(stringToReplace,colorAppender).replace("-appName",appHeader.toUpperCase())
				htmlFile.write(finalReport)
			}
		} else {
			println "Dashboard File Not Present"
		}			
	} catch(err) {
		println "Error During buildQualityDashboard method Call"
	}
}

/**	
 * This method is generally used for databaseOperations like insert & update only,
 * is called internally from other methods defined in this script to capture the required 
 * details like jenkins_user_id, Buid_No and status of each stage during the build.
 * Database configuration details & query structure are stored as environment variables in jenkins so any changes in these deatails requires admin priveleges
 */
 /*
def databaseOperation(String query,String operation) {
	try {
		if(query != null && query != "") {
			def dbAndtableName = "${dbName}.${table_name}"
			def sql = Sql.newInstance("${dbHost}${dbName}","${dbUserName}","${dbPassword}","${dbDriver}")
			
			
			if(operation != null && operation != "") {
				if(operation.equalsIgnoreCase("insert")) {
					def fullInsertQuery = "insert into "+dbAndtableName+"(job_name,application_name,user_id,build_id,git_checkout_status,deployment_environment,application_category,build_dt) values("+query
					sql.execute(fullInsertQuery)
					sql.close()
				} else if (operation.equalsIgnoreCase("update")){
					def fullUpdateQuery = "update "+dbAndtableName+" SET "+query
					sql.execute(fullUpdateQuery)
					sql.close()
				}
			}
			
		}
	} catch(err) {
		currentBuild.result = "BUILD FAILED"
		throw err
	}
}*/


/**
 * This method check users cloudhub deployable permission based on the provided parameters and return success/failed result.
 * Parameters:
 *  orgName - Business group name
 *  envName - Environment where application are deploying
 *  userName - Cloudhub username.
*/
def validateCloudhubUser(String orgName, String envName, String userName) {
	def slurper = new JsonSlurper()
    def outputStream = new StringBuffer();

    def command = ["curl", "-k","-H","Content-Type: application/json","-H","client_id: ${access_validator_client_id}","-H","client_secret: ${access_validator_client_secret}","-x http://awsduproxy.unilever.com:8080","-X","GET","http://unilever-platform-access-validator.ir-e1.cloudhub.io/api/roles/admin?orgId=${orgName}&env=${envName}&userName=${userName}"]    
    def proc = command.execute();
    proc.waitForProcessOutput(outputStream, System.err)

	def result = slurper.parseText(outputStream.toString())
	return result.status
}


/**
*This method to copyPackageTos3 specific version of artifacts and delete older artifacts of same.
*/
def copyPackageTos3(repository) {
	try
	{
	withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'ai-devops-artifactorys3user', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
	sh """
	set +x
	export PATH=/usr/local/bin/:$PATH
	export HTTP_PROXY=http://awsduproxy.unilever.com:8080
	aws s3 cp --no-progress '$env.WORKSPACE'/target/${pom.artifactId}-${pom.version}.zip s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version}/${BUILD_NUMBER}/${pom.artifactId}-${pom.version}.zip ;
	aws s3 cp --no-progress '$env.WORKSPACE'/src/devops/${userInput}.properties s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version}/${BUILD_NUMBER}/${userInput}.properties 
	aws s3 cp --no-progress '$env.WORKSPACE'/src/devops/Build.properties s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version}/${BUILD_NUMBER}/Build.properties 
	aws s3 ls s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version} --recursive | sort | head -n -${artifactsCount} | awk '{\$1=\$2=\$3=""; print \$0}' | sed 's/^[ \t]*//' | while read -r line ; do
	echo "Removing older artifacts \"\${line}\"";
	aws s3 rm "s3://aiedu-adaptive-integration-artifacts-bucket-01/\${line}";
	done
	set -x
	"""
	}
	} catch(err) {
	println("Unable to upload artifacts to S3-Bucket");
	throw err
	}

}

/**
*This method to download artifacts and relevant files from S3-Bucket.
*/
def downloadPackageFroms3(repository) {
	try
	{
	//Clean artifactory folder if exists
	def folder = new File("$env.WORKSPACE/artifactory")
	
	if(folder.exists()) {
		FileUtils.cleanDirectory(folder)
	} else {
		new File("$env.WORKSPACE/artifactory").mkdirs()
	}
	withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'ai-devops-artifactorys3user', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY']]) {
	sh """
	set +x
	export PATH=/usr/local/bin/:$PATH
	export HTTP_PROXY=http://awsduproxy.unilever.com:8080
	aws s3 cp --no-progress s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version}/${BUILD_NUMBER}/${pom.artifactId}-${pom.version}.zip '$env.WORKSPACE'/artifactory 
	aws s3 cp --no-progress s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version}/${BUILD_NUMBER}/${userInput}.properties '$env.WORKSPACE'/artifactory 
	aws s3 cp --no-progress s3://aiedu-adaptive-integration-artifacts-bucket-01/${repository}/${pom.artifactId}/${pom.version}/${BUILD_NUMBER}/Build.properties '$env.WORKSPACE'/artifactory 
	set -x
	"""
	}
	} catch(err) {
	println("Unable to download artifacts from S3-Bucket");
	throw err
	}
}

/**************************************************************
 * Pipeline Stages
 **************************************************************/

/**
 * Stage to checkout sourcecode from SCM repository.
 */
 
def gitCheckout() {
	stage ('Git Checkout') {
		node {
			try {
				timeout(time: 5, unit: 'MINUTES') {
					// Send notification to users about the initiation of the build.
					notifyBuild('BUILD STARTED')
													
					// Checkout the source code from Bitbucket
					checkout scm
					
					// Capture checkout branch name
					def gitbranchname = env.BRANCH_NAME
					
					// Prompt user input
					def choiceitems = null
				
					// Change prompt based on checked out branch.
					if (gitbranchname.contains("master") || gitbranchname.contains("release")) {
						choiceitems = "dev\nsit\nuat\nqa\nreg\nprod"
					} else {
						choiceitems = "dev\nsit\nuat\nqa\nreg"
					}
					userInput = input(id: 'userInput', message: 'Enter environment details.', 
						parameters: [
							[$class: 'ChoiceParameterDefinition', name: 'Environment', choices: choiceitems, description: 'Deployment environment']
						]
					)

					// Checkout dashboard files from repository
					checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'Dashboard'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'JENKINSBB', url: 'git@bitbucket.org:adaptiveintegrationteam/thresholdalert.git']]]) 
					
					// Capture properties file values and perform pre-validation.
					prevalidation()
				
					// Capture login user name
					def user
					wrap([$class: 'BuildUser']) {
						user = env.BUILD_USER
					}
					
					// Capture Jenkins job name
					int jobStartIndex = "$JOB_NAME".indexOf("/")
					int jobEndIndex = "$JOB_NAME".indexOf("/",jobStartIndex+1)
					jobName = "$JOB_NAME".substring(jobStartIndex+1,jobEndIndex) + ""
					
					// Capture application category
					def app_Category
					
					if(buildProps.applicationCategory != null || buildProps.applicationCategory != "") {
						app_Category = buildProps.applicationCategory
					} else {
						app_Category = deployProps.Deploymenttype
					}					
					
					// Insert KPI record to database
					//def insertQuery = "'" + jobName + "'," + deployProps.Clappname + ",'" + user + "',$BUILD_NUMBER,'Success','" + userInput + "'," + app_Category + ",'$BUILD_TIMESTAMP')"
					//databaseOperation(insertQuery,"insert")

					// Feature to save secured key in DB table
					/*
					def sql = Sql.newInstance("${dbHost}${dbName}","${dbUserName}","${dbPassword}","${dbDriver}")
					def query = "replace into pf_app_secretkey(application_name,secret_key,environment_name,environment_type,is_encrypted) values('" + userInput + "-" + deployProps.Clappname.replace("\'","") + "'," + deployProps.Securekey + "," + deployProps.Clenvname + ",'" + userInput +"',1)"
					sql.execute(query)
					sql.close()
					*/
				}
			} catch(err) {
				notifyBuild(currentBuild.result)				
				throw err
			}
		}
	}
}

/**
 * Stage to perform maven build and execute static code analysis.
 */

def mavenVerify() {
	stage ('Build & Mule Lint Static Code Analysis') {
		node {
			try {
			
				// Set discard old builds to max 10
				properties([buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10')), pipelineTriggers([])])
				
				// Execute maven clean package
				sh "${mvnHome}//bin//mvn -B -U clean package -DskipMunitTests -Dmule.env=${userInput}"
				
				// Archive the lint reports and publish html reports
				step([$class: 'ArtifactArchiver', artifacts: '**/target/lint/*', fingerprint: true])
				
				publishHTML(target:[
					allowMissing: false, 
					alwaysLinkToLastBuild: true, 
					keepAll: true, 
					reportDir: '\\target\\lint', 
					reportFiles: 'SummaryReport.html', 
					reportName: 'Mule Lint Report'
				])
				
				// If lint report is successfully generated then update unified quality dashboard.
				def lintFile = new File("${workspace}/target/lint/SummaryReport.html")
				String lintCoverage = ""
				String percentVal = "";					
				
				if(lintFile.exists()) {
					lintCoverage = new File("${workspace}/target/lint/SummaryReport.html").getText('UTF-8')
					int startIndex = lintCoverage.indexOf("<span>")+6
					int endIndex = lintCoverage.indexOf("</span>")
					lintSpanIndex = lintCoverage.substring(startIndex,endIndex)
					percentVal = lintCoverage.substring(startIndex,endIndex-1)+".00"
					
					double doubleValue = Double.valueOf(percentVal)
					buildQualityDashboard(doubleValue,">Lint-Report-Pending","Lint",lintSpanIndex)
					lint_build_Status = "Success"			
				} else {
					println "SummaryReport.html Not Present"
				}
				
				// Update KPI results to DB
				//def updateQuery = "build_code_analysis='"+lint_build_Status+"', lint_rule_status='"+lintSpanIndex+"' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				//databaseOperation(updateQuery,"update")
			} catch(err) {  
		        // If there is an exception thrown, the build failed
		        currentBuild.result = "BUILD FAILED"
				//def updateQuery = "build_code_analysis='"+lint_build_Status+"', lint_rule_status='"+lintSpanIndex+"' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				//databaseOperation(updateQuery,"update")
		        notifyBuild(currentBuild.result)				
		        throw err
			}	   
		}
	}
}

/**
 * Stage to perform MUnit analysis.
 */

def runMUnitTests() {
	stage ('MUnit Testing & Code Coverage Analysis') {
		node {
			try {
				// Execute maven test goal			
				sh ('#!/bin/sh\n' + "${mvnHome}//bin//mvn -B test -Dhttp.proxyHost=awsduproxy.unilever.com -Dhttp.proxyPort=8080 -Dhttps.proxyHost=awsduproxy.unilever.com -Dhttps.proxyPort=8080 -Dsecure.key=$decsecurekey -Dmule.env=${userInput}")
				
				// Archive the MUnit reports and publish html reports
				step([$class: 'ArtifactArchiver', artifacts: '**/target/*.zip', fingerprint: true])
				step([$class: 'ArtifactArchiver', artifacts: '**/target/munit-reports/coverage/summary.html'])
				
				publishHTML(target:[
					allowMissing: false, 
					alwaysLinkToLastBuild: true, 
					keepAll: true, 
					reportDir: '\\target\\munit-reports\\coverage', 
					reportFiles: 'summary.html', 
					reportName: 'MUnit Code Coverage'
				])
				
				// If MUnit report is successfully generated then update unified quality dashboard.
				def munitFile = new File("${workspace}/target/munit-reports/coverage/summary.html")
				String munitCoverage = ""
				String percentVal = "";

				if(munitFile.exists()){
					munitCoverage = new File("${workspace}/target/munit-reports/coverage/summary.html").getText('UTF-8')
					int startIndex = munitCoverage.indexOf("<span>")+6
					int endIndex = munitCoverage.indexOf("</span>")
					munitSpanIndex = munitCoverage.substring(startIndex,endIndex)
					percentVal = munitCoverage.substring(startIndex,endIndex-1)
					
					double doubleValue = Double.valueOf(percentVal)
					buildQualityDashboard(doubleValue,">Munit-Report-Pending","Munit",munitSpanIndex)
					munit_Build_Status = "Success"
				} else {
					println "SummaryReport.html Not Present"
				}
				
				// Update KPI results to DB
				//def updateQuery = "munit_test_report='"+munit_Build_Status+"', munit_coverage_report='"+munitSpanIndex+"' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				//databaseOperation(updateQuery,"update")
			} catch(err) {
				step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
				//def updateQuery = "munit_test_report='"+munit_Build_Status+"', munit_coverage_report='"+munitSpanIndex+"' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
				currentBuild.result = "BUILD FAILED"
				//databaseOperation(updateQuery,"update")
				notifyBuild(currentBuild.result)					
				throw err
			}
		}
	}
}

/**
 * Stage to deploy artifacts to JFrog artifactory.
 */
def deployToArtifactory() {
	stage ('Deploy to AWS S3Bucket') {
		node {
			try {
				def repository = "libs-snapshot-local"
				def artifactsCount = "6"
				pom = readMavenPom file: 'pom.xml'		
				
				if (userInput.equals("prod")) {
					repository = "libs-release-local"
					artifactsCount = "15"
				}
				//Upload the artifacts to S3-Bucket
				copyPackageTos3(repository)
				
			  } catch(err) {
				currentBuild.result = "BUILD FAILED"
		        notifyBuild(currentBuild.result)				
				throw err
			}
		}
	}
}

/**
 * Stage to deploy artifacts to CloudHub
 */
def deployToCloudhub() {
	stage ('Deploy to Cloudhub') {
		node {
			try {
				def repository = "libs-snapshot-local"

				pom = readMavenPom file: 'pom.xml'

				if (userInput.equals("prod")) {
					repository = "libs-release-local"
				}
			
				// Download the artifacts from S3-Bucket.
				downloadPackageFroms3(repository)
				
				// Read build and deploy properties downloaded from artifactory.
				def props = readProperties file: "artifactory/Build.properties"
				def envProps = readProperties file: "artifactory/${userInput}.properties"

				// Check for minimum MUnit and Mule Lint score for QA and Prod environments.		
				def depEnv = userInput
				if(depEnv.contains("sit") || depEnv.contains("uat") || depEnv.contains("qa") || depEnv.contains("reg") || depEnv.contains("prod")) {
					String percentRemovedMunit = munitSpanIndex.substring(0,(munitSpanIndex.length()) - 1)
					String percentRemovedLint = lintSpanIndex.substring(0,(lintSpanIndex.length()) - 1)
					double munitDoubleVal = Double.valueOf(percentRemovedMunit)
					double lintDoubleVal = Double.valueOf(percentRemovedLint)
				
					if(munitDoubleVal == 0.00 || lintDoubleVal == 0.00){
						error("MUnit / Mule Lint cannot be skipped for SIT, UAT, QA, REG and Prod environment.")
					} else if(munitDoubleVal < 80 || lintDoubleVal < 75){
						error("Not suffice minimal validation score - MUnit: 80% / Mule Lint: 75%")
					} else {
						println "Suffice minimal validation score for deployment."
					}
				}

				//calls method anypointCliModifyCLapps deploy to cloudhub enable/disable  objectstorev2/statiIp/Persistques anypoint Runtime properties
				anypointCliModifyCLapps()	
				// Deploy to cloudhub
				//sh ('#!/bin/sh\n' + "${mvnHome}//bin//mvn -B deploy -DskipPerformanceTests -DskipMunitTests -Dhttp.proxyHost=awsduproxy.unilever.com -Dhttp.proxyPort=8080 -Dhttps.proxyHost=awsduproxy.unilever.com -Dhttps.proxyPort=8080 -Dmule.env=${userInput} -Dmule.deployment.type=${envProps.Deploymenttype} -Dmule.jj.version=${envProps.Muleversion} -Dcl.user.name=${clUserName} -Dcl.user.password=${clPassword} -Dcl.env.name=${envProps.Clenvname} -Dcl.region=${envProps.Clregion} -Dcl.workers=${envProps.Clworkers} -Dcl.worker.type=${envProps.Clworkertype} -Dcl.app.name=${userInput}-${envProps.Clappname} -Dcl.ClientID=${envProps.ClClientID} -Dcl.ClientSecret=${envProps.ClClientSecret} -Dcl.SecureKey=$decsecurekey -Dcl.target.type=${envProps.Cltargettype} -Dcl.target=${envProps.Cltarget}")
				
				//calls method disableLogsAnypoint to disable Cloughub logs in anypoint	Runtime
				//disableLogsAnypoint()
				//def updateQuery = "cloudhub_dep_status='Success', build_dt='$BUILD_TIMESTAMP', mule_runtime_version="+envProps.Muleversion+", cloudhub_environment="+envProps.Clenvname+", cloudhub_workers="+envProps.Clworkers+", cloudhub_worker_type="+envProps.Clworkertype+", cloudhub_region="+envProps.Clregion+", deployment_type=" + envProps.Deploymenttype + " where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"	
				//databaseOperation(updateQuery,"update")
				//delete the artifacts folder		
				sh "rm -rf  artifactory/"
			} catch(err){
				currentBuild.result = "BUILD FAILED"
				//def updateQuery = "cloudhub_dep_status='Failed' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				//databaseOperation(updateQuery,"update")
		        notifyBuild(currentBuild.result)				
				throw err
			}
		}
	}
}
/**
 * Stage for performance testing using Apache JMeter
 */
def runPerformanceTests() {
	stage ('Performance Testing') {
		node {
			try {
				sh "${mvnHome}//bin//mvn -B -U verify -DskipMunitTests -Denv=${userInput}"
				sh "java -jar ./target/jmeter/bin/ApacheJMeter-3.2.jar -g ./target/jmeter/results/*.jtl -o ./target/dashboard || true"
				step([$class: 'ArtifactArchiver', artifacts: '**/target/dashboard/*', allowEmptyArchive: true, fingerprint: true])
				
				publishHTML(target:[
				allowMissing: true, 
				alwaysLinkToLastBuild: true, 
				keepAll: true, 
				reportDir: '\\target\\dashboard', 
				reportFiles: 'index.html', 
				reportName: 'Performance Report'
				])
				
				def performanceCoverage = new File("${workspace}/target/dashboard/content/js/dashboard.js")
				
				String percentVal = "";				
				String performancePercent=""
				
				if(performanceCoverage.exists()){
					performanceCoverage = new File("${workspace}/target/dashboard/content/js/dashboard.js").getText('UTF-8')
					int startIndex = performanceCoverage.indexOf("OkPercent")+12
					int endIndex = performanceCoverage.indexOf(", \"KoPercent")
					performancePercent = performanceCoverage.substring(startIndex,endIndex)
					percentVal = performanceCoverage.substring(startIndex,endIndex)
					println "Performance Exists"
					double doubleValue = Double.valueOf(percentVal)
					buildQualityDashboard(doubleValue,">Performance-Report-Pending","Performance",performancePercent+"%")
					publishDashBoard()
				} else {
					println "Performance not calculated"
				}
	
				//def updateQuery = "performance_status='Success', performance_percentage='"+performancePercent+"' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				//databaseOperation(updateQuery,"update")
			} catch(err) {
				performance_percentage = "No Performance Report"
				//def updateQuery = "performance_status='Failed', performance_percentage='"+performancePercent+"' where build_id=$BUILD_NUMBER and job_name='"+jobName+"'"
				currentBuild.result = "BUILD FAILED"
				//databaseOperation(updateQuery,"update")
		        notifyBuild(currentBuild.result)				
				throw err				
			}
		}
	}
}

/**
 * This stage can be deleted.
 * 
 */
def insertToAurora() {
	stage ('Push To KPI Dashboard') {
		node() {
			notifyBuild(currentBuild.result)
		}
	}
}

def runSonarQubeAnalysis() {
	stage ('SonarQube Analysis'){
		node {
			/**
			props = setProperties()
			
			withSonarQubeEnv('SonarQube') {
				sh "${mvnHome}//bin//mvn -DskipMunitTests -Dsonar.projectKey=${props.SQPKey} -Dsonar.projectName=${props.SQPName} -Dsonar.projectDescription=${props.SQPDescription} -Dsonar.projectVersion=${props.SQPVersion} -Dsonar.sources=${props.SQPSources} sonar:sonar"
				sh "sleep 10"
			}

			timeout(time: 1, unit: 'HOURS') {
				def qg = waitForQualityGate()
				if (qg.status != 'OK') {
					error "Pipeline aborted due to quality gate failure: ${qg.status}"
				}
			}
			*/
		}
	}
}

return this; 