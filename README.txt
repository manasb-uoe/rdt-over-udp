NAME: MANAS BAJAJ
STUDENT NUMBER: s1265676

Solutions for question 1a are in basic_framework directory
============================================================

How to run Receiver1a: 
----------------------
javac Receiver1a.java
java Receiver1a <port> <absolute_path_of_received_file>
for example: java Receiver1a 9876 /afs/inf.ed.ac.uk/user/s12/s1265676/Desktop/test_copy.jpg

How to run Sender1a: 
---------------------
javac Sender1a.java
java Sender1a <host_name> <port> <absolute_path_of_sent_file>
for example: java Sender1a localhost 9876 /afs/inf.ed.ac.uk/user/s12/s1265676/Desktop/test.jpg


Solutions for question 1b are in stop_and_wait directory
=========================================================

How to run Receiver1b: 
----------------------
javac Receiver1b.java
java Receiver1b <port> <absolute_path_of_received_file>
for example: java Receiver1b 9876 /afs/inf.ed.ac.uk/user/s12/s1265676/Desktop/test_copy.jpg

How to run Sender1b: 
---------------------
javac Sender1b.java
java Sender1b <host_name> <port> <absolute_path_of_sent_file> <retry_timeout>
for example: java Sender1b localhost 9876 /afs/inf.ed.ac.uk/user/s12/s1265676/Desktop/test.jpg 50



