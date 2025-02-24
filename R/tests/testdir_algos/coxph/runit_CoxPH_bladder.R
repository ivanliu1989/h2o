setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source('../../findNSourceUtils.R')

test.CoxPH.bladder <- function(conn) {
  bladder$rx   <- as.factor(bladder$rx)
  bladder$enum <- as.factor(bladder$enum)
  bladder.h2o  <- as.h2o(conn, bladder, key = "bladder.h2o")

  Log.info("H2O Cox PH Model of bladder Data Set using Efron's Approximation; 1 predictor\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = "size", y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o")
  bladder.coxph <- coxph(Surv(stop, event) ~ size, data = bladder)
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Efron's Approximation; 4 predictors\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = c("enum", "rx", "number", "size"), y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o")
  bladder.coxph <- coxph(Surv(stop, event) ~ enum + rx + number + size, data = bladder)
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Efron's Approximation; 4 predictors and case weights\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = c("enum", "rx", "number", "size"), y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", weights = "id")
  bladder.coxph <- coxph(Surv(stop, event) ~ enum + rx + number + size, data = bladder, weights = bladder$id)
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Efron's Approximation; init = 0.2\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = "size", y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", init = - 0.1)
  bladder.coxph <- coxph(Surv(stop, event) ~ size, data = bladder, init = - 0.1)
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Efron's Approximation; iter.max = 1\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = "size", y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", control = h2o.coxph.control(iter.max = 1))
  bladder.coxph <- coxph(Surv(stop, event) ~ size, data = bladder,
                         control = coxph.control(iter.max = 1))
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Breslow's Approximation; 1 predictor\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = "size", y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", ties = "breslow")
  bladder.coxph <-
    coxph(Surv(stop, event) ~ size, data = bladder, ties = "breslow")
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph, tolerance = 1e-7)

  Log.info("H2O Cox PH Model of bladder Data Set using Breslow's Approximation; 4 predictors\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = c("enum", "rx", "number", "size"), y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", ties = "breslow")
  bladder.coxph <-
    coxph(Surv(stop, event) ~ enum + rx + number + size, data = bladder, ties = "breslow")
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Breslow's Approximation; 4 predictors and case weights\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = c("enum", "rx", "number", "size"), y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", weights = "id", ties = "breslow")
  bladder.coxph <-
    coxph(Surv(stop, event) ~ enum + rx + number + size, data = bladder, weights = bladder$id, ties = "breslow")
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Breslow's Approximation; init = 0.2\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = "size", y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", ties = "breslow", init = - 0.1)
  bladder.coxph <-
    coxph(Surv(stop, event) ~ size, data = bladder, ties = "breslow",
          init = - 0.1)
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  Log.info("H2O Cox PH Model of bladder Data Set using Breslow's Approximation; iter.max = 1\n")
  bladder.coxph.h2o <-
    h2o.coxph(x = "size", y = c("stop", "event"), data = bladder.h2o,
              key = "bladmod.h2o", ties = "breslow",
              control = h2o.coxph.control(iter.max = 1))
  bladder.coxph <-
    coxph(Surv(stop, event) ~ size, data = bladder, ties = "breslow",
          control = coxph.control(iter.max = 1))
  checkCoxPHModel(bladder.coxph.h2o, bladder.coxph)

  testEnd()
}

doTest("Cox PH Model Test: Bladder", test.CoxPH.bladder)
