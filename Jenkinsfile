timestamps {
    node {
        stage('Checkout') {
            checkout scm
        }

        stage('Test') {
            sh './gradlew test'
        }

        stage('Build') {
            jobDsl(additionalClasspath: 'src/main/groovy', removedJobAction: 'DELETE', removedViewAction: 'DELETE',
                    targets: 'src/main/groovy/*.groovy', unstableOnDeprecation: true)
        }
    }
}
