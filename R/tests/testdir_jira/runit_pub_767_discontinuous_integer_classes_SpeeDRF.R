######################################################################
# Test for PUB-767
#
#  Handle discontinuity in integer respones classes
#   covtype.altered has classes: -1, 2, 3, 6, 10000
#
# From disk:
#  cut -d, -f55 covtype.altered | sort | uniq -c | sort
#
#  -1     20510
#   1     211840
#   2     283301
#   3     35754
#   4     2747
#   6     17367
#   10000 9493
#
#  From h2o:
#     V55     C1
#     2 283301
#    -1  20510
#     6  17367
#     1 211840
#     4   2747
#     3  35754
# 10000   9493
#
######################################################################

setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
options(echo=TRUE)
source('../findNSourceUtils.R')

test.pub.767 <- function(conn) {
  Log.info('Importing the altered covtype data from smalldata.')
  cov <- h2o.importFile(conn, normalizePath(locate('smalldata/covtype/covtype.altered.gz')), 'cov')
  
  Log.info('Print head of dataset')
  Log.info(head(cov))

  Log.info("Show the counts of each response level")
  cnts <- h2o::ddply(cov, .("V55"), nrow)
  print(as.data.frame(cnts))
 
  m <- h2o.randomForest(x = 1:54, y = 55, data = cov, ntree = 10, depth = 100) 

  print(m)  
  testEnd()
}

doTest("PUB-767: randomForest on discontinuous integer classes.", test.pub.767)
