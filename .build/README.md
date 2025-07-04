Building and Testing with the helper sripts
-------------------------------------------

Information on building and testing beyond the use of ant.
All scripts also print help if the first argument is `-h`.

Code Checks and Lints
---------------------

Run in docker:

    .build/docker/check-code.sh


Run without docker:

    .build/check-code.sh


Run in docker with a specific jdk.
The following applies to all build scripts.

    .build/docker/check-code.sh 11


Run in docker with a specific build path.
This permits parallel builds off the same source path.
The following applies to all build scripts.

    build_dir=/tmp/cass_Mtu462n .build/docker/check-code.sh


Building Artifacts (tarball and maven)
-------------------------------------

Build with docker:

    .build/docker/build-artifacts.sh


Build without docker:

    .build/build-artifacts.sh


Build in docker with a specific jdk:

    .build/docker/build-artifacts.sh 11


Building Debian and RedHat packages
-----------------------------------

The packaging scripts are only intended to be used with docker.

Build:

    .build/docker/build-debian.sh
    .build/docker/build-redhat.sh


Build with a specific jdk:

    .build/docker/build-debian.sh 11
    .build/docker/build-redhat.sh rpm 11


Build with legacy noboolean and a specific jdk:

    .build/docker/build-redhat.sh noboolean 11


Running Tests
-------------

Running unit tests with docker:

    .build/docker/run-tests.sh -a test


Running unittests without docker:

    .build/run-tests.sh -a test


Running only a split of unittests, with docker:

    .build/docker/run-tests.sh -a test -c 1/64


Running unittests with a specific jdk with docker:

    .build/docker/run-tests.sh -a test -c 1/64 -j 11


Running only unit tests matching a regexp, with docker:

    .build/docker/run-tests.sh -a test -t VerifyTest -j 11
    .build/docker/run-tests.sh -a test -t "Compaction*Test$" -j 11


Running other types of tests with docker:

    .build/docker/run-tests.sh -a test
    .build/docker/run-tests.sh -a stress-test
    .build/docker/run-tests.sh -a fqltool-test
    .build/docker/run-tests.sh -a microbench
    .build/docker/run-tests.sh -a test-cdc
    .build/docker/run-tests.sh -a test-compression
    .build/docker/run-tests.sh -a test-oa
    .build/docker/run-tests.sh -a test-system-keyspace-directory
    .build/docker/run-tests.sh -a test-tries
    .build/docker/run-tests.sh -a test-burn
    .build/docker/run-tests.sh -a long-test
    .build/docker/run-tests.sh -a cqlsh-test
    .build/docker/run-tests.sh -a jvm-dtest
    .build/docker/run-tests.sh -a jvm-dtest-upgrade
    .build/docker/run-tests.sh -a dtest
    .build/docker/run-tests.sh -a dtest-novnode
    .build/docker/run-tests.sh -a dtest-offheap
    .build/docker/run-tests.sh -a dtest-large
    .build/docker/run-tests.sh -a dtest-large-novnode
    .build/docker/run-tests.sh -a dtest-upgrade
    .build/docker/run-tests.sh -a dtest-upgrade-large

Repeating tests

Just add the '-repeat' suffix to the test type and pass in the repeat arguments

    .build/run-tests.sh -a jvm-dtest-repeat -t BooleanTest -e REPEATED_TESTS_COUNT=2
    .build/docker/run-tests.sh -a jvm-dtest-repeat -t BooleanTest -e REPEATED_TESTS_COUNT=2 -j 11

Fail fast with repeating tests is done with REPEATED_TESTS_STOP_ON_FAILURE

    .build/run-tests.sh -a jvm-dtest-repeat -t BooleanTest -e REPEATED_TESTS_COUNT=2 -e REPEATED_TESTS_STOP_ON_FAILURE=false

Running python dtests without docker:

    .build/run-python-dtests.sh dtest


Other test types without docker:

    .build/run-tests.sh jvm-test


Other python dtest types without docker:

    .build/run-python-dtests.sh dtest-upgrade-large

Debugging test scripts:

    DEBUG=true .build/docker/run-tests.sh -a test


Running Sonar analysis (experimental)
-------------------------------------

Run:

    ant sonar

Sonar analysis requires the SonarQube server to be available. If there
is already some SonarQube server, it can be used by setting the
following env variables:

    SONAR_HOST_URL=http://sonar.example.com
    SONAR_CASSANDRA_TOKEN=cassandra-project-analysis-token
    SONAR_PROJECT_KEY=<key of the Cassandra project in SonarQube>

If SonarQube server is not available, one can be started locally in
a Docker container. The following command will create a SonarQube
container and start the server:

    ant sonar-create-server

The server will be available at http://localhost:9000 with admin
credentials admin/password. The Docker container named `sonarqube`
is created and left running. When using this local SonarQube server,
no env variables to configure url, token, or project key are needed,
and the analysis can be run right away with `ant sonar`.

After the analysis, the server remains running so that one can
inspect the results.

To stop the local SonarQube server:

    ant sonar-stop-server

However, this command just stops the Docker container without removing
it. It allows to start the container later with:

    docker container start sonarqube

and access previous analysis results. To drop the container, run:

    docker container rm sonarqube

When `SONAR_HOST_URL` is not provided, the script assumes a dedicated
local instance of the SonarQube server and sets it up automatically,
which includes creating a project, setting up the quality profile, and
quality gate from the configuration stored in
[sonar-quality-profile.xml](sonar%2Fsonar-quality-profile.xml) and
[sonar-quality-gate.json](sonar%2Fsonar-quality-gate.json)
respectively. To run the analysis with a custom quality profile, start
the server using `ant sonar-create-server`, create a project manually,
and set up a desired quality profile for it. Then, create the analysis
token for the project and export the following env variables:

    SONAR_HOST_URL="http://127.0.0.1:9000"
    SONAR_CASSANDRA_TOKEN="<token>"
    SONAR_PROJECT_KEY="<key of the Cassandra project in SonarQube>"

The analysis can be run with `ant sonar`.
