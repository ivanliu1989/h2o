import unittest, sys, random, time
sys.path.extend(['.','..','py'])
import h2o, h2o_cmd, h2o_browse as h2b, h2o_import as h2i, h2o_hosts, h2o_jobs, h2o_exec as h2e
import h2o_util

import multiprocessing, os, signal, time
from multiprocessing import Process, Queue

print "dueling inc"
print "a variant with c(0,0) style vectors everywhere, including compares"
print "restrict outstanding to number of nodes"

# overrides the calc below if not None
NODES = 3
OUTSTANDING = NODES
TRIALMAX = 10
INIT_ONLY = False
TEST_MUX_STORE = True
INIT_MANY = False

# problem with keyboard interrupt described
# http://bryceboe.com/2012/02/14/python-multiprocessing-pool-and-keyboardinterrupt-revisited/
def function_no_keyboard_intr(result_queue, function, *args):
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    result_queue.put(function(*args))
    return True

def execit(n, bucket, path, src_key, hex_key, timeoutSecs=60, retryDelaySecs=1, pollTimeoutSecs=30):
    np = (n) % len(h2o.nodes)
    np1 = (n+1) % len(h2o.nodes)
    np2 = (n+2) % len(h2o.nodes)
    hex_key = np1
    if INIT_ONLY: 
        print "Do Nothing"
        return hex_key

    for j in range(1):
        execExpr = "(r%s==c(1,1)) ? c(1,1) : c(0,0);" % np
        print "Sending request to node: %s" % h2o.nodes[np1],
        (resultExec, fpResult) = h2e.exec_expr(node=h2o.nodes[np1], execExpr=execExpr, timeoutSecs=30)

        if INIT_MANY:
            execExpr = "(r%s==c(1,1)) ? c(1,1) : c(0,0);" % np1
            print "Sending request to node: %s" % h2o.nodes[np1],
            (resultExec, fpResult) = h2e.exec_expr(node=h2o.nodes[np1], execExpr=execExpr, timeoutSecs=30)

            execExpr = "(r%s==c(1,1)) ? c(1,1) : c(0,0);" % np2
            print "Sending request to node: %s" % h2o.nodes[np1],
            (resultExec, fpResult) = h2e.exec_expr(node=h2o.nodes[np1], execExpr=execExpr, timeoutSecs=30)

    return hex_key


class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global SEED, localhost
        SEED = h2o.setup_random_seed()

        localhost = h2o.decide_if_localhost()
        h2o.beta_features = True # for the beta tab in the browser
        if (localhost):
            h2o.build_cloud(node_count=NODES, java_heap_GB=4)
                # use_hdfs=True, hdfs_name_node='172.16.2.176', hdfs_version='cdh4'
        else:
            h2o_hosts.build_cloud_with_hosts(java_heap_GB=4)
                # use_hdfs=True, hdfs_name_node='172.16.2.176', hdfs_version='cdh4'

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_exec2_multi_node3(self):
        h2o.beta_features = True

        for initTrial in range(1):
            for node in h2o.nodes:
                # get this key known to this node
                execExpr = "r0 = c(0,0); r1 = c(0,0); r2 = c(0,0);"
                print "Sending request to node: %s" % node
                h2e.exec_expr(node=node, execExpr=execExpr, timeoutSecs=30)

                if TEST_MUX_STORE:
                    # test the store expression
                    execExpr = "(r1==c(0,0)) ? c(0,0) : c(1,1)"
                    print "Sending request to node: %s" % node
                    h2e.exec_expr(node=node, execExpr=execExpr, timeoutSecs=30)

        global OUTSTANDING
        if not OUTSTANDING:
            OUTSTANDING = min(10, len(h2o.nodes))

        execTrial = 0
        worker_resultq = multiprocessing.Queue()
        while execTrial <= TRIALMAX:
            start = time.time()
            workers = []
            for o in range(OUTSTANDING):
                np = execTrial % len(h2o.nodes)
                retryDelaySecs = 5
                timeoutSecs = 60
                bucket = None
                csvPathname = None
                src_key = None
                hex_key = 'a'
                tmp = multiprocessing.Process(target=function_no_keyboard_intr,
                    args=(worker_resultq, execit, np, bucket, csvPathname, src_key, hex_key, timeoutSecs, retryDelaySecs))
                tmp.start()
                workers.append(tmp)
                execTrial += 1

            # Exec doesn't get tracked as a job. So can still have outstanding
            # now sync on them
            for worker in workers:
                try:
                    # this should synchronize
                    worker.join()
                    print "worker joined:", worker
                    # don't need him any more
                    worker.terminate()
                    hex_key = worker_resultq.get(timeout=2)
                except KeyboardInterrupt:
                    print 'parent received ctrl-c'
                    for worker in workers:
                        worker.terminate()
                        worker.join()
            elapsed = time.time() - start
            print "Group end at #", execTrial, "completed in", "%6.2f" % elapsed, "seconds.", \
                "%d pct. of timeout" % ((elapsed*100)/timeoutSecs)

if __name__ == '__main__':
    h2o.unit_main()
