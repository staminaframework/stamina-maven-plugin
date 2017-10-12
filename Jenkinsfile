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
                withMaven(maven: 'M3', mavenLocalRepo: '.repository') {
                    sh "mvn -Dmaven.test.skip=true clean install"
                }
            }
        }
        stage("Verify") {
            steps {
                withMaven(maven: 'M3', mavenLocalRepo: '.repository') {
                    sh "mvn -Dmaven.install.skip=true integration-test"
                }
            }
        }
        stage("Archive") {
            steps {
                withMaven(maven: 'M3', mavenLocalRepo: '.repository') {
                    sh "mvn -Dmaven.install.skip=true -Dmaven.test.skip=true deploy"
                }
            }
        }
    }
}
