#!/usr/bin/env bash

PEGASUS_DOCKER_DIR="/home/wutao1/git/pegasus/docker"

function run_test() {
    pushd ${PEGASUS_DOCKER_DIR}
        ./start_onebox.sh || exit 1
    popd

    lein run test --time-limit 180 --concurrency 10 --nodes-file=nodes.txt || exit 1

    pushd ${PEGASUS_DOCKER_DIR}
        ./clear_onebox.sh || exit 1
    popd
}

TEST_COUNT=10
for i in $(seq "${TEST_COUNT}"); do
    run_test
done
