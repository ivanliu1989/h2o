import unittest, time, sys, random
sys.path.extend(['.','..','py'])
import h2o, h2o_cmd, h2o_hosts, h2o_browse as h2b, h2o_import as h2i
import getpass

RANDOM_UDP_DROP = False
class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        # assume we're at 0xdata with it's hdfs namenode
        global localhost
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(use_hdfs=True, hdfs_version='cdh4', hdfs_name_node='mr-0x6', random_udp_drop=RANDOM_UDP_DROP)
        else:
            h2o_hosts.build_cloud_with_hosts(1, use_hdfs=True, hdfs_version='cdh4', hdfs_name_node='mr-0x6', random_udp_drop=RANDOM_UDP_DROP)

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_hdfs_cdh4_fvec(self):
        print "\nLoad a list of files from HDFS, parse and do 1 RF tree"
        print "\nYou can try running as hduser/hduser if fail"
        # larger set in my local dir
        # fails because classes aren't integers
        #    "allstate_claim_prediction_train_set.zip",
        csvFilenameAll = [
            # "3G_poker_shuffle"
            ("and-testing.data", 60),
            ### "arcene2_train.both",
            ### "arcene_train.both",
            ### "bestbuy_test.csv",
            ("covtype.data", 60),
            ("covtype4x.shuffle.data", 60),
            # "four_billion_rows.csv",
            ("hhp.unbalanced.012.data.gz", 60),
            ("hhp.unbalanced.data.gz", 60),
            ("leads.csv", 60),
            # ("covtype.169x.data", 600),
            ("prostate_long_1G.csv", 600),
            # ("airlines_all.csv", 900),
        ]

        # pick 8 randomly!
        if (1==0):
            csvFilenameList = random.sample(csvFilenameAll,8)
        # Alternatively: do the list in order! Note the order is easy to hard
        else:
            csvFilenameList = csvFilenameAll

        # pop open a browser on the cloud
        # h2b.browseTheCloud()

        trial = 0
        print "try importing /tmp2"
        d = h2i.import_only(path="tmp2/*", schema='hdfs', timeoutSecs=1000)
        print h2o.dump_json(d)
        d = h2i.import_only(path="datasets/*", schema='hdfs', timeoutSecs=1000)
        print h2o.dump_json(d)
        for (csvFilename, timeoutSecs) in csvFilenameList:
            # creates csvFilename.hex from file in hdfs dir 
            print "Loading", csvFilename, 'from HDFS'
            start = time.time()
            hex_key = "a.hex"
            csvPathname = "datasets/" + csvFilename
            parseResult = h2i.import_parse(path=csvPathname, schema='hdfs', hex_key=hex_key, header=0, timeoutSecs=1000)
            print "hdfs parse of", csvPathname, "took", time.time() - start, 'secs'

            start = time.time()
            print "Saving", csvFilename, 'to HDFS'

            print "Using /tmp2 to avoid the '.' prefixed files in /tmp2 (kills import)"
            print "Unique per-user to avoid permission issues"
            username = getpass.getuser()
            # reuse the file name to avoid running out of space
            csvPathname = "tmp2/a%s.%s.csv" % ('_h2o_export_files', username)

            path = "hdfs://"+ h2o.nodes[0].hdfs_name_node + "/" + csvPathname
            h2o.nodes[0].export_files(src_key=hex_key, path=path, force=1, timeoutSecs=timeoutSecs)
            print "export_files of", hex_key, "to", path, "took", time.time() - start, 'secs'
            trial += 1

            print "Re-Loading", csvFilename, 'from HDFS'
            start = time.time()
            hex_key = "a2.hex"
            time.sleep(2)
            d = h2i.import_only(path=csvPathname, schema='hdfs', timeoutSecs=1000)
            print h2o.dump_json(d)
            parseResult = h2i.import_parse(path=csvPathname, schema='hdfs', hex_key=hex_key, header=0, timeoutSecs=1000)
            print "hdfs re-parse of", csvPathname, "took", time.time() - start, 'secs'

if __name__ == '__main__':
    h2o.unit_main()
