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
from collections import defaultdict

appMap = defaultdict(list) #用以存放用户对应文件总大小的字典

# Application-Id	    Application-Name	    Application-Type	      User	     Queue	             State	       Final-State	       Progress	            Tracking-URL

def get_app_duration():
    global appMap
    get_application_running = "yarn application -list"
    running_list = commands.getoutput(get_application_running).split('\n')
    #print running_list[2]
    print running_list[-1]
    for line in running_list[3:]:
        app_status_list = []
        line_list = line.split()
        for enum in line_list:
            app_status_list.append(enum)
        #print line
        curr_app_id = line.split()[0]
        #curr_app_name = line.split()[1]
        #curr_user = line.split()[2]
        #curr_state = line.split()[3]
        #状态列表
        #app_status_list.append(curr_app_id)
        #app_status_list.append(curr_app_name)
        #app_status_list.append(curr_user)
        #app_status_list.append(curr_state)
        #print app_status_list
        #print curr_app_id
        get_application_status = "yarn application -status " + curr_app_id
        curr_app_status = commands.getoutput(get_application_status).split('\n')
        #print curr_app_status
        for status_line in curr_app_status:
            global start_time
            if "Start-Time" in status_line:
                start_time = status_line.split(':')[1]
                break
        #print start_time
        curr_time =  int(round(time.time() * 1000))
        #app从提交到当前，已经经过到时间
        app_duration = (curr_time - int(start_time))/1000
        appMap[app_duration] = app_status_list
        
        #print app_duration 
            
        
if __name__ == "__main__":
    get_app_duration()
    appMap = sorted(appMap.items(), key=lambda d: d[0], reverse = True)
    #print appMap 

    #遍历这个top运行时间的字典
    content = "任务运行时间从大到小如下： " + "\r\n"
    for key in appMap:
        content += "任务运行时间: " + "\r\n"
    	content +=  str(key[0]) + "\r\n"
        content +=  "对应的任务信息: " + "\r\n"
        for line in key[1]:
            content +=  line + "\r\n"
        content +=  "\r\n"

    #print content
    output_file = "hadoop1_report.txt"
    with open(output_file,'w') as wF:
        wF.write(content)
