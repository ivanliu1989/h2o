options(echo=F)
local({r <- getOption("repos"); r["CRAN"] <- "http://cran.us.r-project.org"; options(repos = r)})
if (!"R.utils" %in% rownames(installed.packages())) install.packages("R.utils")
if (!"plyr" %in% rownames(installed.packages())) install.packages("plyr")
#if (!"rgl" %in% rownames(installed.packages())) install.packages("rgl")
if (!"randomForest" %in% rownames(installed.packages())) install.packages("randomForest")
library(R.utils)

setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source("h2oR.R")
source("utilsR.R")

ipPort <- get_args(commandArgs(trailingOnly = TRUE))
failed <<- F

removePackage <- function(package) {
    failed <<- F
    tryCatch(remove.packages(package), error = function(e) {failed <<- T})
    if (! failed) {
        print(paste("Removed package", package))
    }
}

removePackage('h2o')

failed <<- F
tryCatch(library(h2o), error = function(e) {failed <<- T})
if (! failed) {
    stop("Failed to remove h2o library")
}

h2o_r_package_file <- NULL
dir_to_search = normalizePath("../../../target/R", winslash = "/")
files = dir(dir_to_search)
for (i in 1:length(files)) {
    f = files[i]
    # print(f)
    arr = strsplit(f, '\\.')[[1]]
    # print(arr)
    lastidx = length(arr)
    suffix = arr[lastidx]
    # print(paste("SUFFIX", suffix))
    if (suffix == "gz") {
        h2o_r_package_file = f #arr[lastidx]
        break
    }
}

# if (is.null(h2o_r_package_file)) {
#    stop(paste("H2O package not found in", dir_to_search))
# }

install.packages("h2o",
                 repos = c(H2O = paste0(ifelse(.Platform$OS.type == "windows", "file:", "file://"),
                                        dir_to_search),
                           getOption("repos")))
library(h2o)
h2o.init(ip            = ipPort[[1]], 
         port          = ipPort[[2]], 
         startH2O      = FALSE)

##generate master_seed

seed <- NULL
MASTER_SEED <- FALSE
if (file.exists("../master_seed")) {
    MASTER_SEED <<- TRUE
    seed <- read.table("../master_seed")[[1]]
    SEED <<- seed
}
seed <- setupRandomSeed(seed, suppress = TRUE)
if (! file.exists("../master_seed")) {
    write.table(seed, "../master_seed", row.names = F, col.names = F)
}

