#!/usr/bin/env groovy
pipeline {
    agent any
    stages {
        stage("Checkout") {
            steps {
                checkout scm
            }
        }
        stage("Build") {
            steps {
                withMaven(maven: 'M3') {
                    sh "mvn -Prelease -Dmaven.test.skip=true clean deploy"
                }
            }
        }
        stage("Verify") {
            steps {
                withMaven(maven: 'M3') {
                    sh "mvn -Dmaven.install.skip=true integration-test"
                }
            }
        }
    }
}
