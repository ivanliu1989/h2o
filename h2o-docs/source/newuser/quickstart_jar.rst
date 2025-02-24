.. _GettingStartedFromaZipFile: 

Getting Started From a Downloaded Zip File
==========================================


Quick Start Video
"""""""""""""""""

.. raw:: html

	<object width="425" height="344"><param name="movie" value="http://www.youtube.com/v/ZpTydwTWocQ&hl=en&fs=1"></param><param name="allowFullScreen" value="true"></param><embed src="http://www.youtube.com/v/ZpTydwTWocQ&hl=en&fs=1" type="application/x-shockwave-flash" allowfullscreen="true" width="425" height="344"></embed></object>



Step-by-Step Walk-Through
"""""""""""""""""""""""""
1. Download the latest release of H\ :sub:`2`\ O as a .zip file from the
   H\ :sub:`2`\ O `downloads page <http://0xdata.com/download/>`_.

2. From your terminal change your working directory to the same directory where your .zip file is saved.

3. From your terminal, unzip the .zip file.  For example:

::

  unzip h2o-1.7.0.520.zip

4. At the prompt enter the following commands. 

::

  cd h2o-1.7.0.520  #change working directory to the downloaded file
  java -Xmx1g -jar h2o.jar #run the basic java command to start h2o

5. Wait a few moments and output similar to the following will appear in your terminal window:

::

  Irenes-MacBook-Pro:target Irene$ java -Xmx3g -jar h2o.jar 
  09:54:05.400 main      INFO WATER: ----- H2O started -----
  09:54:05.402 main      INFO WATER: Build git branch: master
  09:54:05.402 main      INFO WATER: Build git hash: e44ee2cec508140fc6312e3d6874df8069eac669
  09:54:05.402 main      INFO WATER: Build git describe: nn-2-3032-ge44ee2c-dirty
  09:54:05.402 main      INFO WATER: Build project version: 2.1.0.99999
  09:54:05.402 main      INFO WATER: Built by: 'Irene'
  09:54:05.402 main      INFO WATER: Built on: 'Tue Feb  4 09:45:26 PST 2014'
  09:54:05.402 main      INFO WATER: Java availableProcessors: 8
  09:54:05.403 main      INFO WATER: Java heap totalMemory: 0.24 gb
  09:54:05.403 main      INFO WATER: Java heap maxMemory: 2.67 gb
  09:54:05.404 main      INFO WATER: Java version: Java 1.7.0_51 (from Oracle Corporation)
  09:54:05.404 main      INFO WATER: OS   version: Mac OS X 10.9.1 (x86_64)
  09:54:05.404 main      INFO WATER: ICE root: '/tmp/h2o-Irene'
  09:54:05.407 main      INFO WATER: Possible IP Address: en0 (en0), fe80:0:0:0:6203:8ff:fe91:950a%4
  09:54:05.407 main      INFO WATER: Possible IP Address: en0 (en0), 192.168.0.4
  09:54:05.408 main      INFO WATER: Possible IP Address: lo0 (lo0), fe80:0:0:0:0:0:0:1%1
  09:54:05.408 main      INFO WATER: Possible IP Address: lo0 (lo0), 0:0:0:0:0:0:0:1
  09:54:05.408 main      INFO WATER: Possible IP Address: lo0 (lo0), 127.0.0.1
  09:54:05.431 main      INFO WATER: Internal communication uses port:
  54322 Listening for HTTP and REST traffic
				   on  http: //192.168.0.4:54321/
  09:54:05.471 main      INFO WATER: H2O cloud name: 'Irene'
  09:54:05.472 main      INFO WATER: (v2.1.0.99999) 'Irene' on
                       /192.168.0.4:54321, discovery address /229.58.14.243:58682
  09:54:05.472 main      INFO WATER: If you have trouble connecting,
  try SSH tunneling from your local machine (e.g., via port 55555): 1. Open a terminal and run 'ssh -L 55555:localhost:54321 Irene@192.168.0.4'
  2. Point your browser to http://localhost:55555
  09:54:05.475 main      INFO WATER: Cloud of size 1 formed [/192.168.0.4:54321]
  09:54:05.475 main      INFO WATER: Log dir: '/tmp/h2o-Irene/h2ologs'


5. Point your web-browser to:

::

  http://localhost:54321/ 

The user interface will appear in your browser, and now H\ :sub:`2`\ O is ready to go.

.. WARNING::
  On Windows systems, Internet Explorer is frequently blocked due to
  security settings.  If you cannot reach http://localhost:54321 try a
  different web browser such as Firefox or Chrome.

Useful Notes
""""""""""""   

First time users may need to download and install `Java <https://www.java.com/en/download/>`_
in order to run H\ :sub:`2`\ O. The program is available free on the web,
and can be quickly installed. Even though you will use Java to 
run H\ :sub:`2`\ O, no programming is necessary.
Java 1.6+ is supported for regular users but for developers that modify and recompile
code Java 1.7 is recommended.

The command used to launch H\ :sub:`2`\ O exercises the argument -Xmx1g. Xmx is the
amount of memory given to H\ :sub:`2`\ O.  If your data set is large,
give H\ :sub:`2`\ O more memory by running -Xmx4g instead of -Xmx1g which will allocate
4g instead of 1g to your instance. For best performance the about of memory H\ :sub:`2`\ O
is using should be four times the size size of your data but never more than the total
amount of memory on your computer.

For more command line options continue to read :ref:`Javahelp`.
