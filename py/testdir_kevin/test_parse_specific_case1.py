import unittest, random, sys, time, os
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd, h2o_hosts, h2o_import as h2i
import codecs, unicodedata
print "create some specific small datasets with exp row/col combinations"
print "This is a known fail for both row and col. Leading unmatched double quote issue"

tryList = [
('''\
"a,b,c,d
"a,b,c,d
"a,b,c,d
"a,b,c,d
"a,b,c,d
a,b,c,d
"a,b,c,d
"a,b,c,d
"a,b,c,d
"a,b,c,d
''', 
10, 4, [0,0,0,0], ['Enum', 'Enum', 'Enum', 'Enum']),
]

def write_syn_dataset(csvPathname, dataset):
    dsf = codecs.open(csvPathname, encoding='utf-8', mode='w+')
    encoded = dataset.decode('utf-8')
    print "utf8:" , repr(encoded), type(encoded)
    print "str or utf8:" , repr(dataset), type(dataset)
    dsf.write(dataset)
    dsf.close()

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global SEED, localhost
        SEED = h2o.setup_random_seed()
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(java_heap_GB=1)
        else:
            h2o_hosts.build_cloud_with_hosts()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_parse_specific_case1(self):
        SYNDATASETS_DIR = h2o.make_syn_dir()
        hex_key = "a.hex"

        for (dataset, expNumRows, expNumCols, expNaCnt, expType) in tryList:
            csvFilename = 'specific_' + str(expNumRows) + "x" + str(expNumCols) + '.csv'
            csvPathname = SYNDATASETS_DIR + '/' + csvFilename
            write_syn_dataset(csvPathname, dataset)

            parseResult = h2i.import_parse(path=csvPathname, schema='put', header=0,
                hex_key=hex_key, timeoutSecs=10, doSummary=False)
            inspect = h2o_cmd.runInspect(None, parseResult['destination_key'], timeoutSecs=60)
            
            print "inspect:", h2o.dump_json(inspect)
            numRows = inspect['numRows']
            self.assertEqual(numRows, expNumRows, msg='Wrong numRows: %s Expected: %s' % (numRows, expNumRows))
            numCols = inspect['numCols']
            self.assertEqual(numCols, expNumCols, msg='Wrong numCols: %s Expected: %s' % (numCols, expNumCols))

            # this is required for the test setup
            assert(len(expNaCnt)>=expNumCols)
            assert(len(expType)>=expNumCols)

            for k in range(expNumCols):
                naCnt = inspect['cols'][k]['naCnt']
                self.assertEqual(expNaCnt[k], naCnt, msg='col %s naCnt %d should be %s' % (k, naCnt, expNaCnt[k]))
                stype = inspect['cols'][k]['type']
                self.assertEqual(expType[k], stype, msg='col %s type %s should be %s' % (k, stype, expType[k]))

if __name__ == '__main__':
    h2o.unit_main()
