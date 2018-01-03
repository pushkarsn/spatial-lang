# This is called by regression_run.sh

import gspread
import pygsheets
import sys
import os
from oauth2client.service_account import ServiceAccountCredentials
import datetime

#1 = branch
#2 = tid
#3 = appname
#4 = pass
#5 = cycles
#6 = hash
#7 = apphash
#8 = csv list of properties

# tid = sys.argv[2]


# # gspread auth
# json_key = '/home/mattfel/regression/synth/key.json'
# scope = [
#     'https://spreadsheets.google.com/feeds',
#     'https://www.googleapis.com/auth/drive'
# ]
# credentials = ServiceAccountCredentials.from_json_keyfile_name(json_key, scope)

# pygsheets auth
json_key = '/home/mattfel/regression/synth/pygsheets_key.json'
gc = pygsheets.authorize(outh_file = json_key)

# sh = gc.open(sys.argv[1] + " Performance")
if (sys.argv[1] == "fpga"):
	sh = gc.open_by_key("1CMeHtxCU4D2u12m5UzGyKfB3WGlZy_Ycw_hBEi59XH8")
elif (sys.argv[1] == "develop"):
	sh = gc.open_by_key("13GW9IDtg0EFLYEERnAVMq4cGM7EKg2NXF4VsQrUp0iw")
elif (sys.argv[1] == "retime"):
	sh = gc.open_by_key("1glAFF586AuSqDxemwGD208yajf9WBqQUTrwctgsW--A")
elif (sys.argv[1] == "syncMem"):
	sh = gc.open_by_key("1TTzOAntqxLJFqmhLfvodlepXSwE4tgte1nd93NDpNC8")
elif (sys.argv[1] == "pre-master"):
	sh = gc.open_by_key("18lj4_mBza_908JU0K2II8d6jPhV57KktGaI27h_R1-s")
elif (sys.argv[1] == "master"):
	sh = gc.open_by_key("1eAVNnz2170dgAiSywvYeeip6c4Yw6MrPTXxYkJYbHWo")
else:
	print("No spreadsheet for " + sys.argv[4])
	exit()

# Get column
worksheet = sh.worksheet_by_title('Timestamps') # Select worksheet by index
lol = worksheet.get_all_values()
if (sys.argv[3] in lol[0]):
	col=lol[0].index(sys.argv[3])+1
	print("Col is %d" % col)
else:
	col=len(lol[0])+1
	print("Col is %d" % col)
	worksheet = sh.worksheet_by_title('Timestamps')
	worksheet.update_cell((1,col),sys.argv[3])
	worksheet = sh.worksheet_by_title('Properties')
	worksheet.update_cell((1,col),sys.argv[3])
	worksheet = sh.worksheet_by_title('Runtime')
	worksheet.update_cell((1,2*col-7),sys.argv[3])
# Find row, since tid is now unsafe
tid = -1
for i in range(2, len(lol)):
	if (lol[i][0] == sys.argv[6] and lol[i][1] == sys.argv[7]):
		tid = i + 1
		break


# Page 0 - Timestamps
stamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')

worksheet = sh.worksheet_by_title('Timestamps') # Select worksheet by index
worksheet.update_cell((tid,col), stamp)

# Page 1 - Runtime
worksheet = sh.worksheet_by_title('Runtime') # Select worksheet by index
worksheet.update_cell((tid,2*col-7),sys.argv[5])
worksheet.update_cell((tid,2*col-6),sys.argv[4])

# Page 2 - Properties
worksheet = sh.worksheet_by_title('Properties') # Select worksheet by index
worksheet.update_cell((tid,col),sys.argv[4])
lol = worksheet.get_all_values()
for prop in sys.argv[8].split(","):
	# Find row
	found = False
	for i in range(2, len(lol)):
		if (lol[i][4] == prop):
			worksheet.update_cell((i+1, col), prop)
			found = True
	if (found == False):
		worksheet.update_cell((len(lol)+1,5), prop)
		worksheet.update_cell((len(lol),col), prop)

# Page 3 - STATUS
worksheet = sh.worksheet_by_title('STATUS')
worksheet.update_cell((22,3),stamp)
worksheet.update_cell((22,4),os.uname()[1])