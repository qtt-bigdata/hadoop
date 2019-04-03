# -*- coding: UTF-8 -*-
#!/usr/bin/env python
import subprocess
import datetime
import time
import re
import sys
import os
import commands
import multiprocessing

def hdfs_performance_test(name):
    count = 0
    cmd_clean = "nohup yarn jar /usr/lib/hadoop-current/share/hadoop/mapreduce/hadoop-mapreduce-client-jobclient-2.7.2-tests.jar TestDFSIO -D mapreduce.job.queuename=root.bigdata.data -clean > /dev/null 2>&1 &"
    cmd_write = "nohup yarn jar /usr/lib/hadoop-current/share/hadoop/mapreduce/hadoop-mapreduce-client-jobclient-2.7.2-tests.jar TestDFSIO -D mapreduce.job.queuename=root.bigdata.data -write -nrFiles 10 -fileSize 10MB > /dev/null 2>&1 &"
    cmd_read = "nohup yarn jar /usr/lib/hadoop-current/share/hadoop/mapreduce/hadoop-mapreduce-client-jobclient-2.7.2-tests.jar TestDFSIO -D mapreduce.job.queuename=root.bigdata.data -read -nrFiles 10 -fileSize 10MB > /dev/null 2>&1 &"
    while count < 1:
        done = "unnone"
        subprocess.call(cmd_clean, shell=True)
        #确保清除了再写，受缓存影响数据会不准确
        while done != "":
            done = commands.getoutput('ps -ef | grep -i "jobclient-2.7.2-tests.jar TestDFSIO" | grep -v grep')
        done = "unnone"
        subprocess.call(cmd_write, shell=True)
        while done != "":
            done = commands.getoutput('ps -ef | grep -i "jobclient-2.7.2-tests.jar TestDFSIO" | grep -v grep')
        time_use =  commands.getoutput("cat  TestDFSIO_results.log | grep -i 'exec time' | awk '{print $5}' | awk 'END {print}'")
        print "hdfs write time use: " + time_use
        count += 1
        subprocess.call(cmd_read, shell=True)
        done = "unnone"
        while done != "":
            done = commands.getoutput('ps -ef | grep -i "jobclient-2.7.2-tests.jar TestDFSIO" | grep -v grep') 
            time_use =  commands.getoutput("cat  TestDFSIO_results.log | grep -i 'exec time' | awk '{print $5}' | awk 'END {print}'")
        print "hdfs read time use: " + time_use

def yarn_performance_test():
    average_time = 0.0 
    max_time = 0.0
    min_time = 0.0
    curr_time = 0.0
    time_list = []
    cmd = "nohup yarn jar /usr/lib/hadoop-current/share/hadoop/mapreduce/hadoop-mapreduce-examples-2.7.2.jar pi -D mapreduce.job.queuename=root.bigdata.data 1 1 > test.log 2>&1 &"
    count = 0
    while count < 1:
        done = "unnone"
        subprocess.call(cmd, shell=True)
        while done != "":
            done = commands.getoutput('ps -ef | grep -i "examples-2.7.2.jar pi" | grep -v grep')
        time_use =  commands.getoutput("cat test.log | grep -i 'Job Finished' | awk '{print $4}'") 
        print "yarn job run time: " + time_use
        #if time_use != "":
        #    curr_time = double(time_use)
        #    time_list.append(curr_time)      
        count += 1
    #print time_list

if __name__ == "__main__":
    p1 = multiprocessing.Process(target=yarn_performance_test, args = ())
    p2 = multiprocessing.Process(target=hdfs_performance_test, args = ("hdfs", ))

    p1.start()
    p2.start()
