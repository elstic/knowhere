int total_timeout_minutes = 60*2
def knowhere_wheel=''
pipeline {
    agent {
        { label 'arm' }
    }

    options {
        timeout(time: total_timeout_minutes, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '30')
        parallelsAlwaysFailFast()
        disableConcurrentBuilds(abortPrevious: true)
        preserveStashes(buildCount: 10)
    }
    stages {
        stage("Build"){
            steps {
                container("build"){
                    script{
                        withCredentials([usernamePassword(credentialsId: 'ArmLogin', usernameVariable: 'JENKINS_ARM_USERNAME', passwordVariable: 'JENKINS_ARM_PWD')]) {
                                USER = "${JENKINS_ARM_USERNAME}"
                                PWD = "${JENKINS_ARM_PWD}"
                                sh 'sshpass -p ${PWD} ssh zilliz@10.100.31.37 -p 22'
                        }
                        sh 'pwd'
                        def date = sh(returnStdout: true, script: 'date +%Y%m%d').trim()
                        def gitShortCommit = sh(returnStdout: true, script: "echo ${env.GIT_COMMIT} | cut -b 1-7 ").trim()
                        version="${env.CHANGE_ID}.${date}.${gitShortCommit}"
                        sh 'docker images'
                        sh 'docker run -i -t -d --cap-add=SYS_ADMIN --cap-add=DAC_READ_SEARCH -m 32g --cpus=8 --privileged c119e183244a  /bin/bash'
                        result = sh(script: "<docker run -i -t -d --cap-add=SYS_ADMIN --cap-add=DAC_READ_SEARCH -m 32g --cpus=8 --privileged c119e183244a  /bin/bash>", returnStdout: true).trim()

                        sh 'cd  wh-test'
                        sh 'docker cp knowhere_aarch64.patch ${result}:/'
                        sh 'docker exec -it ${result} /bin/bash'
                        sh 'mkdir /home/data/milvus'
                        sh 'mkdir wh'
                        sh 'mount -t cifs -o username=test,password=Fantast1c,vers=1.0  //172.16.70.249/test/milvus  /home/data/milvus'
                        sh 'mv knowhere_aarch64.patch  wh/'
                        sh 'cd wh/'
                        sh 'git clone -b refactoring https://github.com/milvus-io/knowhere.git'
                        sh 'cd knowhere'
                        sh 'patch -p1 < knowhere_aarch64.patch'
                        sh "apt-get install build-essential libopenblas-dev ninja-build git -y"
                        sh "git config --global --add safe.directory '*'"
                        sh "git submodule update --recursive --init"
                        sh "mkdir build"
                        sh "cd build/ && cmake .. -DCMAKE_BUILD_TYPE=Release -DWITH_UT=ON -DWITH_DISKANN=ON -G Ninja"
                        sh "pwd"
                        sh "ls -la"
                        sh "cd build/ && ninja -v"
                        sh "cd .."
                        sh "cd python  && VERSION=${version} python3 setup.py bdist_wheel"
                        dir('python/dist'){
                        knowhere_wheel=sh(returnStdout: true, script: 'ls | grep .whl').trim()
                        archiveArtifacts artifacts: "${knowhere_wheel}", followSymlinks: false
                        }
                        // stash knowhere info for rebuild E2E Test only
                        sh "echo ${knowhere_wheel} > knowhere.txt"
                        stash includes: 'knowhere.txt', name: 'knowhereWheel'
                    }
                }
            }
        }
        stage("Test"){
            steps {
                script{
                    if ("${knowhere_wheel}"==''){
                        dir ("knowhereWheel"){
                            try{
                                unstash 'knowhereWheel'
                                knowhere_wheel=sh(returnStdout: true, script: 'cat knowhere.txt | tr -d \'\n\r\'')
                            }catch(e){
                                error "No knowhereWheel info remained ,please rerun build to build new package."
                            }
                        }
                    }
                    checkout([$class: 'GitSCM', branches: [[name: '*/feature-2.0']], extensions: [],
                    userRemoteConfigs: [[credentialsId: 'milvus-ci', url: 'https://github.com/milvus-io/knowhere-test.git']]])
                    dir('tests'){
                      unarchive mapping: ["${knowhere_wheel}": "${knowhere_wheel}"]
                      sh "ls -lah"
                      sh "nvidia-smi"
                      sh "pip3 install ${knowhere_wheel} \
                          && pip3 install -r requirements.txt --timeout 30 --retries 6  && pytest -v -m 'L0 and cpu'"
                    }
                }
            }
            post{
                always {
                    script{
                        sh 'cp /tmp/knowhere_ci.log knowhere_ci.log'
                        archiveArtifacts artifacts: 'knowhere_ci.log', followSymlinks: false
                    }
                }
            }
        }

    }
}
