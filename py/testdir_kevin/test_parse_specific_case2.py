import unittest, random, sys, time, os
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd, h2o_hosts, h2o_import as h2i
import codecs, unicodedata
print "create some specific small datasets with exp row/col combinations"
print "This is injecting 0x0 (NUL) into a col, with one char after it"

unicodeNum = 0x0
unicodeSymbol = unichr(unicodeNum)

tryList = [
    # the nul char I think is causing extra rows and also wiping out the next char?
    # I got nulls when concat'ing files with dd. may be used for padding somehow?
    ((
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    'a,b,c,d' + unicodeSymbol + 's,n\n'
    ), 10, 5, [0,0,0,0,0], ['Enum', 'Enum', 'Enum', 'Enum', 'Enum']),
]

def write_syn_dataset(csvPathname, dataset):
    dsf = codecs.open(csvPathname, encoding='utf-8', mode='w+')
    encoded = dataset.encode('utf-8')
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

    def test_parse_specific_case2(self):
        SYNDATASETS_DIR = h2o.make_syn_dir()
        hex_key = "a.hex"

        for (dataset, expNumRows, expNumCols, expNaCnt, expType) in tryList:
            csvFilename = 'specific_' + str(expNumRows) + "x" + str(expNumCols) + '.csv'
            csvPathname = SYNDATASETS_DIR + '/' + csvFilename
            write_syn_dataset(csvPathname, dataset)

            # not forcing the column separator
            parseResult = h2i.import_parse(path=csvPathname, schema='put', header=0,
                hex_key=hex_key, timeoutSecs=10, doSummary=False)
            inspect = h2o_cmd.runInspect(None, parseResult['destination_key'], timeoutSecs=60)
            
            numRows = inspect['numRows']
            numCols = inspect['numCols']
            print "inspect:", h2o.dump_json(inspect)
            self.assertEqual(numRows, expNumRows, msg='Using unichr(0x%x) Wrong numRows: %s Expected: %s' % \
                (unicodeNum, numRows, expNumRows))
            numCols = inspect['numCols']
            self.assertEqual(numCols, expNumCols, msg='Using unichr(0x%x) Wrong numCols: %s Expected: %s' % \
                (unicodeNum, numCols, expNumCols))


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
