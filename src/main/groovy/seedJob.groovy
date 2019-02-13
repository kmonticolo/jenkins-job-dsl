import groovy.json.JsonSlurper

GITLAB_URL = 'https://gitlab.schange.com'
// id of git user credentials in Jenkins, will be used in Checkout stage
GIT_CREDENCIALS = 'ea7d2a7d-8582-4a66-9143-6047f0225528'
// Personal access token created in Gitlab https://gitlab.schange.com/profile/personal_access_tokens
// used for calling Gitlab API
PRIVATE_TOKEN = '2DCUXerSEtzk8NhxdhS9'

SONAR_URL = 'http://sonarqube.schange.com'
JENKINS_URL = 'http://10.48.77.35:8080'

// To
String SLACK_TOKEN = 'OzkEFmPqIQFCYsWw8nGkRftU'
// Name of job that it is running below script
String SEED_JOB_NAME = 'dsl-seed-job'
// View which contains all DSL-created jobs
String VIEW_NAME = 'dsl-jobs'

// Main script configuration: name - path to project in Gitlab, kit - name of job that must be triggered after successful build (can be null)
PROJECTS = [[name: "seachange/adv-imsv-inserter", kit: null],
            [name: "seachange/adv-ads", kit: null],
            [name: "seachange/adv-adr", kit: null],
            [name: "seachange/adv-tomcat-base", kit: null],
            [name: "seachange/adv-imsv-mmc-plugin", kit: null],
            [name: "seachange/adv-imsv-esam", kit: null],
            [name: "seachange/adv-common", kit: null]
]

String[] jobNames = [SEED_JOB_NAME]

PROJECTS.each {
    String kitJob = it.kit
    String encodedName = encode((String) it.name)
    Object info = getProjectInfo(encodedName)
    // add webhook to gitlab - will check if there is need to create job for new release branches
    addGitlabWebhook(encodedName, SEED_JOB_NAME, '')

    Object branches = getBranches(encodedName)
    branches.each {
        String branch = it.name
        println("Project: ${info.name}, branch: ${branch}")
        if (branch == 'master' || branch.startsWith("release/")) {
            String jobName = createJobName((String) info.name, (String) it.name)
            jobNames += jobName
            // add webhook to gitlab - will trigger created job when something is pushed to git
            addGitlabWebhook(encodedName, jobName, branch)
            // create new pipeline job
            pipelineJob(jobName) {
                description("Build job for project ${info.name}")
                logRotator {
                    numToKeep(30)
                    artifactNumToKeep(5)
                }
                triggers {
                    gitlabPush {
                        buildOnMergeRequestEvents(false)
                        buildOnPushEvents(true)
                        enableCiSkip(true)
                        commentTrigger('')
                        includeBranches(branch)
                    }
                }
                definition {
                    cps {
                        sandbox()
                        script("""changeAuthors = 'Nothing'

def sendNotification(msg) {
    message = msg + "\\nChange authors: " + changeAuthors
    slackSend (channel: '#adv-java-jenkins', color: '#439FE0', token: '${SLACK_TOKEN}', message: message)
}

try {
    node('ubuntu') {
        stage('Checkout') {
            checkout([\$class: 'GitSCM',
                branches: [[name: '*/${branch}']],
                doGenerateSubmoduleConfigurations: false,
                extensions: [],
                submoduleCfg: [],
                userRemoteConfigs: [[credentialsId: '${GIT_CREDENCIALS}', url: '${info.ssh_url_to_repo}']]])
            changeAuthors = currentBuild.changeSets.collect { set ->
              set.collect { entry -> entry.author.fullName }
            }.flatten()
        }
        stage('Test') {
            ${createTestCommand(branch)}
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/TEST*.xml'
        }
        stage('Build') {
            sh './mvnw package verify -DskipTests'
            dependencyCheckPublisher canComputeNew: false, defaultEncoding: '', healthy: '', pattern: '', unHealthy: ''
            archiveArtifacts artifacts: '**/target/*.jar,**/target/*.war,**/target/rpm/**/*.rpm,**/target/classes/*.txt', fingerprint: true, onlyIfSuccessful: true
            ${createKitJobTrigger(kitJob, branch)}
        }
    }
    ${createQualityGateStage(branch)}
} catch (err) {
    sendNotification("Build failed: \${env.JOB_NAME} [\${env.BUILD_NUMBER}] (<\${env.BUILD_URL}|Open>): \${err}")
    echo "Caught: \${err}"
    currentBuild.result = 'FAILURE'
    
    throw err
}
""")
                    }
                }
            }
        }
    }
}

// create/update view that contains all jobs created above
listView(VIEW_NAME) {
    jobs {
        names(jobNames)
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
}

private Object getBranches(encodedName) {
    def branchesUrl = new URL("${GITLAB_URL}/api/v4/projects/${encodedName}/repository/branches?private_token=${PRIVATE_TOKEN}")
    def branches = new JsonSlurper().parse(branchesUrl.newReader())
    branches
}

private Object getProjectInfo(encodedName) {
    def projectInfoUrl = new URL("${GITLAB_URL}/api/v4/projects/${encodedName}?private_token=${PRIVATE_TOKEN}")
    def info = new JsonSlurper().parse(projectInfoUrl.newReader())
    info
}

private String createJobName(String projectName, String branchName) {
    def name = projectName
    if (branchName.startsWith("release")) {
        def version = branchName.substring(branchName.indexOf('/') + 1)
        name += "-" + version
    }
    name
}

private String createTestCommand(String branch) {
    if (branch == 'master') {
        return """withSonarQubeEnv {
                sh "./mvnw clean test sonar:sonar -Dsonar.host.url=${SONAR_URL}"
            }"""
    }
    return "sh './mvnw clean test'"
}

private String createQualityGateStage(String branch) {
    if (branch == 'master') {
        return """
    stage("Quality Gate") {
        echo 'Waiting for quality gate'
        timeout(time: 15, unit: 'MINUTES') {
            def qg = waitForQualityGate()
            if (qg.status != 'OK') {
                sendNotification("SONAR FAIL: \${env.JOB_NAME} [\${env.BUILD_NUMBER}]: \${qg.status} (<\${env.BUILD_URL}|Open>)")
                echo "Quality gate failure: \${qg.status}"
                currentBuild.result = 'UNSTABLE'
            }
        }
    }
"""
    }
    return ''
}

private String createKitJobTrigger(String kit, String branch) {
    if (kit != null) {
        return "build job: '${kit}', parameters: [string(name: 'BRANCH', value: '${branch}')], wait: false"
    }
    ''
}

private def addGitlabWebhook(String encodedName, String jobName, String branch) {
    def encodedJobName = encode(jobName)
    def url = "${JENKINS_URL}/project/${encodedJobName}"
    def webhooksListUrl = new URL("${GITLAB_URL}/api/v4/projects/${encodedName}/hooks?private_token=${PRIVATE_TOKEN}")
    def webhooks = new JsonSlurper().parse(webhooksListUrl.newReader())
    boolean webhookExists = false
    webhooks.each {
        if (url == (String) it.url) {
            webhookExists = true
        }
    }
    if (!webhookExists) {
        URL webhookAddUrl = new URL("${GITLAB_URL}/api/v4/projects/${encodedName}/hooks?push_events=true&push_events_branch_filter=${branch}&enable_ssl_verification=false&url=${encode(url)}&private_token=${PRIVATE_TOKEN}")
        println("POST ${webhookAddUrl.toString()}")
        HttpURLConnection connection = (HttpURLConnection) webhookAddUrl.openConnection()
        connection.setRequestMethod("POST")
        connection.connect()
        println("Response: ${connection.responseCode}")
    }
}

private String encode(String s) {
    URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
