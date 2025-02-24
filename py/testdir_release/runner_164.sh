#!/bin/bash

# This is critical:
# Ensure that all your children are truly dead when you yourself are killed.
# trap "kill -- -$BASHPID" INT TERM EXIT
# leave out EXIT for now
trap "kill -- -$BASHPID" INT TERM
echo "BASHPID: $BASHPID"
echo "current PID: $$"

source ./runner_setup.sh "$@"

rm -f h2o-nodes.json
if [[ $HOSTNAME == "ch-63" ]]
then
    # in sm land. clean up!
    pssh -h /home/0xdiag/hosts_minus_9 -i 'rm -f -r /home/0xdiag/ice*'
    python ../four_hour_cloud.py -v -cj pytest_config-jenkins-sm32.json &
else
    if [[ $USER == "jenkins" ]]
    then 
        # clean out old ice roots from 0xcust.** (assuming we're going to run as 0xcust..
        # only do this if you're jenksin
        echo "If we use more machines, expand this cleaning list."
        echo "The possibilities should be relatively static over time"
        echo "Could be problems if other threads also using that user on these machines at same time"
        echo "Could make the rm pattern match a "sourcing job", not just 0xcustomer"
        ssh -i ~/.0xcustomer/0xcustomer_id_rsa 0xcustomer@172.16.2.164 \
            'find /home/0xcustomer/ice* -ctime +3 | xargs rm -rf; cd /mnt/0xcustomer-datasets'



        python ../four_hour_cloud.py -cj pytest_config-jenkins.json &
    else
        if [[ $USER == "kevin" ]]
        then
            python ../four_hour_cloud.py -cj pytest_config-kevin.json &
        else
            python ../four_hour_cloud.py &
        fi
    fi 
fi


CLOUD_PID=$!
jobs -l

echo ""
echo "Have to wait until h2o-nodes.json is available from the cloud build. Deleted it above."
echo "spin loop here waiting for it. Since the h2o.jar copy slows each node creation"
echo "it might be 12 secs per node"

while [ ! -f ./h2o-nodes.json ]
do
  sleep 5
done
ls -lt ./h2o-nodes.json


# We now have the h2o-nodes.json, that means we started the jvms
# Shouldn't need to wait for h2o cloud here..
# the test should do the normal cloud-stabilize before it does anything.
# n0.doit uses nosetests so the xml gets created on completion. (n0.doit is a single test thing)
# A little '|| true' hack to make sure we don't fail out if this subtest fails
# test_c1_rel has 1 subtest

# This could be a runner, that loops thru a list of tests.

echo "If it exists, pytest_config-<username>.json in this dir will be used"
echo "i.e. pytest_config-jenkins.json"
echo "Used to run as 0xcust.., with multi-node targets (possibly)"
myPy() {
    DOIT=../testdir_single_jvm/n0.doit
    $DOIT $1/$2 || true
}


if [[ $TEST == "" ]] || [[ $TESTDIR == "" ]]
then
    # problems when import va gets mixed with fvec
    # and keys aren't deleted? Exec starts to convert all (leading to timeout)
    # just get rid of VA stuff
    # myPy c1 test_c1_rel.py
    # myPy c2 test_c2_fvec.py
    myPy c1 test_c1_fvec.py
    myPy c2 test_c2_nongz_fvec.py
    # myPy c3 test_c3_rel.py
    myPy c3 test_c3_exec_copy.py
    myPy c3 test_c3_nongz_fvec.py
    # myPy c4 test_c4_four_billion_rows_fvec.py
    # known failure last
    myPy c5 test_c5_KMeans_sphere_26GB_fvec.py
    myPy c6 test_c6_hdfs_fvec.py
    # fails with summary. currently disable summary
    # doesn't work. key gets locked. forget about it
    # myPy c7 test_c7_rel.py
    # myPy c8 test_c8_rf_airlines_hdfs.py
    # myPy c8 test_c8_rf_airlines_hdfs_fvec.py
    # myPy c9 test_c9_GLM_rc_fvec.py
    myPy c9 test_c9_GLM_airlines_fvec.py
    # this fails? 9/27/14
    # myPy c10 test_c10_glm_fvec.py

    # myPy c2 test_c2_fvec.py
    # myPy c3 test_c3_fvec.py
    # myPy c4 test_c4_four_billion_rows_fvec.py
    # myPy c5 test_c5_KMeans_sphere15_180GB_fvec.py
    # myPy c5 test_c5_KMeans_sphere_67MB_fvec.py
    # myPy c6 test_c6_hdfs_fvec.py
    # myPy c6 test_c6_maprfs_fvec.py

    # causing problems. don't run for now
else
    myPy $TESTDIR $TEST
fi
# If this one fails, fail this script so the bash dies 
# We don't want to hang waiting for the cloud to terminate.
myPy shutdown test_shutdown.py


if ps -p $CLOUD_PID > /dev/null
then
    echo "$CLOUD_PID is still running after shutdown. Will kill"
    kill $CLOUD_PID
fi
ps aux | grep four_hour_cloud

# test_c2_fvec has about 11 subtests inside it, that will be tracked individually by jenkins
# ../testdir_single_jvm/n0.doit test_c2_fvec || true
# We don't want the jenkins job to complete until we kill it, so the cloud stays alive for debug
# also prevents us from overrunning ourselves with cloud building
# If we don't wait, the cloud will get torn down.

jobs -l
echo ""
echo "You can stop this jenkins job now if you want. It's all done"
# 
