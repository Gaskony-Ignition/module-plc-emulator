import re

DATA_TYPE_MAPPING_IGNITION = {"BOOL":"Boolean", "BIT":"Boolean", "SINT":"Int1", "INT":"Int2", "DINT":"Int4", "REAL":"Float4", "STRING":"String"}
DATA_TYPE_MAPPING_SIMULATION = {"BOOL":"Boolean", "BIT":"Boolean", "SINT":"Int16", "INT":"Int16", "DINT":"Int32", "REAL":"Float", "STRING":"String"}
DEFAULT_SIMULATION = {"BOOL":"false", "BIT":"false", "SINT":"0", "INT":"0", "DINT":"0", "REAL":"0", "STRING":""}

def searchOne(regexStr, findStr, defaultStr=None, flags=re.DOTALL):
	import re
	regex = re.compile(regexStr, flags)
	res = regex.search(findStr)
	if res and res.groups():
		return res.group(1)
	else:
		return defaultStr
			
def search(regexStr, findStr, flags=re.DOTALL):
	import re
	regex = re.compile(regexStr, flags)
	res = regex.search(findStr)
	return res.groups()
	
def matchOne(regexStr, findStr, defaultStr=None, flags=re.DOTALL):
	import re
	regex = re.compile(regexStr, flags)
	res = regex.match(findStr)
	if res and res.groups():
		return res.group(1)
	else:
		return defaultStr
	
def findall(regexStr, findStr, flags=re.DOTALL):
	import re
	regex = re.compile(regexStr, flags)
	res = regex.findall(findStr)
	return res
	
def findallOne(regexStr, findStr, flags=re.DOTALL):
	import re
	regex = re.compile(regexStr, flags)
	res = regex.findall(findStr)
	return res[0]

def parseTags(currentUdts, localTags, folder, tagsSection, comments, allowHidden=False, selectTags=False, useStoredValues=True):
	tags = []
	for tagSection in tagsSection.split(";\r\n"):
		if len(tagSection.strip()) > 0:
			tagParts = search("(\w+)(\sOF\s)?", tagSection.strip())
			if tagParts[1] != None:
				tag = {
				  "name": tagParts[0],
				  "description": "",
				  "dataType": "BIT",
				  "arrayLength":None,
				  "selected": selectTags,
				  "simulationFunction":DEFAULT_SIMULATION.get("BIT", None),
				  "hidden": 0
				}
				tags.append(tag)
			else:
				parseTag(currentUdts, localTags, folder, tags, tagSection, "tag", comments, allowHidden, selectTags, useStoredValues)
	return tags
	
def parseTag(currentUdts, localTags, folder, tags, tagSection, sectionType, comments, allowHidden, selectTags, useStoredValues):
	if len(tagSection.strip()) > 0:		
		if sectionType == "dataType":
			tagParts = search("([a-zA-Z0-9_:]+)\s(\w+)(\[[\d+|\,]+\])?\s?(.+)?", tagSection.strip())
		else:
			try:
				tagParts = [val for val in search("([a-zA-Z0-9_:.]+)\sOF\s([a-zA-Z0-9_:.]+)\s?(\[[\d+|\,]+\])?\s?(.+)?", tagSection.strip())]
			except:
				try:
					tagParts = search("([a-zA-Z0-9_:.]+)\s:\s(\w+)(\[[\d+|\,]+\])?\s?(.+)?", tagSection.strip())
				except:
					tagParts = []			
		
		if len(tagParts) > 1:
			if sectionType == "dataType":
				tagName = tagParts[1]
				tagDataType = tagParts[0]
			else:
				tagName = tagParts[0]
				tagDataType = tagParts[1]
				
			if tagDataType.find(".") > -1:
				# Data type comes from an existing UDT
				aliasLocalTag = tagDataType.split(".")[0]
				aliasDataTypeTag = tagDataType.split(".")[1]
				aliasDataType = None
				
				for localTag in localTags:
					if localTag == aliasLocalTag:
						aliasDataType = localTags[localTag]
						break
				
				found = False
				if aliasDataType is not None:
					for currentUdt in currentUdts:
						if currentUdt["name"] == aliasDataType:
							for udtTag in currentUdt["tags"]:
								if udtTag["name"] == aliasDataTypeTag:
									tagDataType = udtTag["dataType"]
									if tagParts[2] != None:
										tagParts[2] = "[%d]" % udtTag["arrayLength"]
									found = True
									break
							break
				
				if not found:
					tagDataType = "BOOL"
			
			tagDescription = ""
			tagComments = {}
			isHidden = 0
			isInOut = -1
			simulationFunction = None
			arrayLength = None
			
			# Look to see if there is an array
			if tagParts[2] != None:
				arrayLengthStr = tagParts[2][1:-1]
				if "," in arrayLengthStr:
					arrayLengthParts = arrayLengthStr.split(",")
					arrayLength = int(arrayLengthParts[0]) * int(arrayLengthParts[1])
				else:
					arrayLength = int(arrayLengthStr)
									
			# Check for the description and hidden fields, if exists
			if tagParts[3] != None:
				isHidden = int(searchOne("Hidden\s\:\=\s(.*?)(\)|\,)", tagParts[3], 0))
				tagDescription = searchOne("Description.*\"(.*?)\"", tagParts[3], "", 0)
				
				rawComments = findall("COMMENT.(.*?)\",", tagParts[3])
				for rawComment in rawComments:
					commentParts = rawComment.split(":=")
					commentTagPath = commentParts[0].strip().split(":")[0].strip()
					comment = commentParts[1].strip()[1:].strip()
					tagComments[commentTagPath] = comment
					
                # added code to look for AOI InOut paramters, as they are not part of the UDT
				isInOut = (tagParts[3].find("InOut"))

				if useStoredValues:
					tagValueParts = tagParts[3].split(":=")
					tagValue = tagValueParts[-1].strip()
					
					if "$00" in tagValue:
						tagValue = tagValue.replace("$00", "")
										
					if "2#" in tagValue:
						tagValue = tagValue.replace("2#", "")
					
					simulationFunction = tagValue
			
			if folder != None:
				comments[folder] = {tagName:tagComments}
			else:
				comments[tagName] = tagComments
			
			if simulationFunction == None:
				simulationFunction = DEFAULT_SIMULATION.get(tagDataType, None)
			
			# Only bring in tags that are not hidden
            # added condition to block InOut paramters from being added to UDT 
			if (not isHidden or allowHidden) and isInOut == -1:	
				tag = {
				  "name": tagName,
				  "description": tagDescription,
				  "dataType": tagDataType,
				  "arrayLength":arrayLength,
				  "selected": selectTags,
				  "simulationFunction":simulationFunction,
				  "hidden": isHidden
				}
				tags.append(tag)
	
def parse(l5kFile, selectTags=False, allowHidden=False, useStoredValues=True):
	import re
 
	udts = []
	tags = []
	programs = []
	comments = {}
	
	udt = {
	  "name": "TIMER",
	  "description": "Built-in timer",
	  "stringFamily": False,
	  "tags": [
		{
		  "name": "DN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "EN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "TT",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "PRE",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "ACC",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		}
	  ]
	}
	udts.append(udt)
	
	udt = {
	  "name": "COUNTER",
	  "description": "Built-in counter",
	  "stringFamily": False,
	  "tags": [
		{
		  "name": "CD",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "CU",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "DN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "OV",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "UN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "PRE",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "ACC",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		}
	  ]
	}
	udts.append(udt)
	

	udt = {
	  "name": "MESSAGE",
	  "description": "Built-in Message",
	  "stringFamily": False,
	  "tags": [
		{
		  "name": "Flags",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "EW",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "ER",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "DN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "ST",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "TO",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "EN_CC",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "ERR",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "EXERR",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "ERR_SRC",
		  "description": "",
		  "dataType": "SINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "DN_LEN",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "REQ_LEN",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "DestinationLink",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "DestinationNode",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "SourceLink",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Class",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Attribute",
		  "description": "",
		  "dataType": "INT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Instance",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "LocalIndex",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Channel",
		  "description": "",
		  "dataType": "SINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Rack",
		  "description": "",
		  "dataType": "SINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Group",
		  "description": "",
		  "dataType": "SINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Slot",
		  "description": "",
		  "dataType": "SINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "Path",
		  "description": "",
		  "dataType": "STRING",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "RemoteIndex",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "RemoteElement",
		  "description": "",
		  "dataType": "STRING",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "UnconnectedTimeout",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "ConnectionRate",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "TimeoutMultiplier",
		  "description": "",
		  "dataType": "SINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		}
	  ]
	}
	udts.append(udt)
	
	udt = {
	  "name": "CONTROL",
	  "description": "Built-in Control",
	  "stringFamily": False,
	  "tags": [
		{
		  "name": "LEN",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "POS",
		  "description": "",
		  "dataType": "DINT",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "EN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "EU",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "DN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"false",
		  "hidden": False
		},
		{
		  "name": "EM",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "ER",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "UL",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "IN",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		},
		{
		  "name": "FD",
		  "description": "",
		  "dataType": "BOOL",
		  "arrayLength":None,
		  "selected": True,
		  "simulationFunction":"0",
		  "hidden": False
		}
	  ]
	}
	udts.append(udt)
 
	if l5kFile != None:		
		# Get the controller information including IP address and slot number, so we can potentially create the PLC connection
		commPath = searchOne("CommPath\s\:\=\s\"(.*?)\"", l5kFile)
		if commPath != None:
			commPathParts = commPath.split("\\")
			ipAddress = commPathParts[1]
			slotNumber = commPathParts[-1]
		else:
			ipAddress = None
			slotNumber = None
		
		# Find all of the data types so we can make UDT definitions
		for dataType in findall("DATATYPE(.*?)END_DATATYPE", l5kFile):
			dataTypeName = dataType[0:dataType.find("(")].strip()
			dataTypeDescription = searchOne("Description.*\"(.*?)\"", dataType, "", 0)
						
			udtTags = []
			tagsSection = dataType[dataType.find(")\r\n")+1:]
			for tagRow in tagsSection.split(";\r\n"):
				parseTag(udts, [], None, udtTags, tagRow, "dataType", comments, allowHidden, True, False)
			
			udt = {
			  "name": dataTypeName,
			  "description": dataTypeDescription,
			  "tags": udtTags,
			  "stringFamily": "FamilyType := StringFamily" in dataType
			}
			udts.append(udt)
		
		# Find all of the add on instructions so we can make UDT definitions
		for dataType in findall("ADD_ON_INSTRUCTION_DEFINITION\s(.*?)END_ADD_ON_INSTRUCTION_DEFINITION\r\n", l5kFile):
			dataTypeName = searchOne("(.*?)\s", dataType, "", 0)
			dataTypeDescription = ""
			
			localTags = {}
			try:
				tagsSection = findallOne("LOCAL_TAGS\r\n(.*?)END_LOCAL_TAGS\r\n", dataType)
				for tagRow in tagsSection.split(";\r\n"):
					try:
						tagParts = search("([a-zA-Z0-9_:.]+)\s:\s(\w+)(\[[\d+|\,]+\])?\s?(.+)?", tagRow.strip())
					except:
						tagParts = []
	
					if len(tagParts):
						localTags[tagParts[0]] = tagParts[1]
			except:
				pass
			
			udtTags = []
			tagsSection = findallOne("PARAMETERS\r\n(.*?)END_PARAMETERS\r\n", dataType)
			for tagRow in tagsSection.split(";\r\n"):
				parseTag(udts, localTags, None, udtTags, tagRow, "tag", comments, allowHidden, True, False)
			
			udt = {
			  "name": dataTypeName,
			  "description": dataTypeDescription,
			  "tags": udtTags,
			  "stringFamily": False
			}
			udts.append(udt)
					
		# Find all of the add on instructions so we can make UDT definitions
		for dataType in findall("ADD_ON_INSTRUCTION_DEFINITION,(.*?)END_ENCODED_DATA\r\n", l5kFile):
			dataTypeName = searchOne("Name.*\"(.*?)\"", dataType, "", 0)
			dataTypeDescription = searchOne("Description.*\"(.*?)\"", dataType, "", 0)
			
			localTags = {}
			try:
				tagsSection = findallOne("LOCAL_TAGS\r\n(.*?)END_LOCAL_TAGS\r\n", dataType)
				for tagRow in tagsSection.split(";\r\n"):
					try:
						tagParts = search("([a-zA-Z0-9_:.]+)\s:\s(\w+)(\[[\d+|\,]+\])?\s?(.+)?", tagRow.strip())
					except:
						tagParts = []
	
					if len(tagParts):
						localTags[tagParts[0]] = tagParts[1]
			except:
				pass
			
			udtTags = []
			tagsSection = findallOne("PARAMETERS\r\n(.*?)END_PARAMETERS\r\n", dataType)
			for tagRow in tagsSection.split(";\r\n"):
				parseTag(udts, localTags, None, udtTags, tagRow, "tag", comments, allowHidden, True, False)
			
			udt = {
			  "name": dataTypeName,
			  "description": dataTypeDescription,
			  "tags": udtTags,
			  "stringFamily": False
			}
			udts.append(udt)
			
		# Find all of the global tags
		try:
			globalSection = findallOne("CONTROLLER(.*?)END_TAG\r\n", l5kFile)
			tagsSection = findallOne("TAG\r\n(.*?)END_TAG\r\n", globalSection + "END_TAG\r\n")
			tags = parseTags(udts, [], None, tagsSection, comments, allowHidden, selectTags, useStoredValues)
		except:
			tags = []

		# Find all programs
		for programSection in findall("\tPROGRAM\s(.*?)END_PROGRAM\r\n", l5kFile):
			programName = searchOne("(\w+)\s(.*?)", programSection, "")
			tagsSection = findallOne("TAG\r\n(.*?)END_TAG\r\n", programSection)
			programTags = parseTags(udts, [], programName, tagsSection, comments, allowHidden, selectTags, useStoredValues)
			programs.append({
				"name": programName,
				"tags": programTags
			})
		
	return (udts, tags, programs, comments)

def addSimulationTag(udts, udtStrings, tagItem, opcItemPath, simulationFunctionValue=None):
	simulationData = []
	if tagItem["selected"]:
		name = tagItem["name"]
		simulationFunction = tagItem["simulationFunction"] if simulationFunctionValue == None else simulationFunctionValue
		dataType = tagItem["dataType"]
		arrayLength = tagItem["arrayLength"]
		
		if simulationFunctionValue == None:
			try:
				simulationFunctionValues = eval(simulationFunction)
			except:
				simulationFunctionValues = None
		else:
			simulationFunctionValues = simulationFunctionValue
		
		if opcItemPath == None:
			opcItemPath = name
		else:
			opcItemPath = "%s.%s" % (opcItemPath, name)
		
		for i in range(1 if arrayLength is None else arrayLength):
			if arrayLength != None:
				realOpcItemPath = "%s[%d]" % (opcItemPath, i)
			else:
				realOpcItemPath = opcItemPath
				
			if arrayLength != None and simulationFunctionValues != None and isinstance(simulationFunctionValues, list) and i < len(simulationFunctionValues):
				realSimulationFunctionValues = simulationFunctionValues[i]
			else:
				realSimulationFunctionValues = simulationFunctionValues
				
			if realSimulationFunctionValues != None and "[" in str(realSimulationFunctionValues):
				realSimulationFunctionValues = None
				
			if dataType in DATA_TYPE_MAPPING_SIMULATION or dataType in udtStrings:
				if dataType in udtStrings:
					dataType = "STRING"
					
				# Simple data type
				simulationRow = ["0",realOpcItemPath,"" if realSimulationFunctionValues == None else str(realSimulationFunctionValues),DATA_TYPE_MAPPING_SIMULATION[dataType]]
				simulationData.append(simulationRow)
			else:
				# UDT instance
				simulationData.extend(getSimulationUDTTags(udts, udtStrings, dataType, realOpcItemPath, realSimulationFunctionValues))
	return simulationData

def getSimulationUDTTags(udts, udtStrings, dataType, opcItemPath, simulationFunctionValues):
	import math
	simulationData = []
	
	if dataType not in udts:
		udts[dataType] = []
	
	udtTags = udts[dataType]
	numBools = 0
	for udtTag in udtTags:
		if not udtTag["hidden"] and udtTag["dataType"] == "BOOL":
			numBools += 1
	
    # modified code such that tag index starts at 1 if any bools exist in the UDT and starts at 0 if no bools exist
	if numBools >= 1:
		tagIdx = 1
	else:
		tagIdx = 0
		
    # added index to increment through the hiddne DINTs that contain the BOOL data
	boolDintIDX = 0
	boolIdx = 0
	for udtTag in udtTags:
		simulationFunctionValue = None
		if not udtTag["hidden"] and simulationFunctionValues != None and isinstance(simulationFunctionValues, list):
			if udtTag["dataType"] == "BOOL" and len(simulationFunctionValues) > 0:
                # modified the code to dyanically index the values at the correct bool DINT position
				simulationFunctionValue = simulationFunctionValues[boolDintIDX] >> boolIdx & 1
            # added code to grab the string value from the string data types native list
			elif udtTag["dataType"] == "STRING" and len(simulationFunctionValues) > 0:
				simulationFunctionValue = simulationFunctionValues[tagIdx][1]
			elif tagIdx < len(simulationFunctionValues):
				simulationFunctionValue = simulationFunctionValues[tagIdx]
			
		simulationData.extend(addSimulationTag(udts, udtStrings, udtTag, opcItemPath, simulationFunctionValue))
		
		if not udtTag["hidden"]:
			if udtTag["dataType"] == "BOOL":
				boolIdx += 1
			else:
				tagIdx += 1
				
        # added code to increment the bool dint index as the location of the bools moves through the hiddne DINTs 	
		# adjust the tag index to jump around the other hidden DINTs that may exist in the data type
		if boolIdx == 32 or boolIdx == 64 or boolIdx == 95 or boolIdx == 127:
			boolDintIDX = tagIdx
			tagIdx += 1
			boolIdx = 0
		

	return simulationData

def generateSimulationCSV(udtItems, globalTagItems, programItems):
	simulationData = []
	simulationHeader = ["Time Interval", "Browse Path", "Value Source", "Data Type"]
	
	# Create a dictionary of all UDT definitions
	udts = {}
	udtStrings = []
	for udtItem in udtItems:
		name = udtItem.header.content.text
		stringFamily = udtItem.header.content.stringFamily
		tags = udtItem.body.viewParams.tags
		if stringFamily:
			udtStrings.append(name)
		else:
			udts[name] = tags
		
	# First go through all of the global tags
	for tagItem in globalTagItems:
		simulationData.extend(addSimulationTag(udts, udtStrings, tagItem, None))
				
	# Now go through all of the program tags
	for programItem in programItems:
		programName = programItem.header.content.text
		programTags = programItem.body.viewParams.tags
		
		for tagItem in programTags:
			simulationData.extend(addSimulationTag(udts, udtStrings, tagItem, programName))
			
	return system.dataset.toCSV(system.dataset.toDataSet(simulationHeader, simulationData))

def addUDTTag(udtPrefix, udts, udtStrings, tagItem, inUDTInstance, udtsNotFound):
	tags = []
	if tagItem["selected"]:
		name = tagItem["name"]
		tagName = name
		description = tagItem["description"]
		dataType = tagItem["dataType"]		
		arrayLength = tagItem["arrayLength"]
		
		addToTags = tags
		if arrayLength != None:
			arrayTags = []
			tags.append({
			  "name": tagName,
			  "tagType": "Folder",
			  "tags": arrayTags
			})
			addToTags = arrayTags
		
		opcItemPath = name
					
		for i in range(1 if arrayLength is None else arrayLength):
			if arrayLength != None:
				tagName = "%d" % i
				realOpcItemPath = "%s[%d]" % (opcItemPath, i)
			else:
				realOpcItemPath = opcItemPath
		
			if dataType in DATA_TYPE_MAPPING_SIMULATION or dataType in udtStrings:
				if dataType in udtStrings:
					dataType = "STRING"
					
				# Simple data type
				if inUDTInstance:
					addToTags.append({
					  "name": tagName,
					  "tagType": "AtomicTag"
					})
				else:
					addToTags.append({
					  "opcItemPath": {
						"bindType": "parameter",
						"binding": "ns\u003d1;s\u003d[{DeviceName}]{TagPrefix}.%s" % realOpcItemPath
					  },
					  "valueSource": "opc",
					  "dataType": DATA_TYPE_MAPPING_IGNITION[dataType],
					  "name": tagName,
					  "documentation": description,
					  "tagType": "AtomicTag",
					  "opcServer": "Ignition OPC UA Server"
					})
			else:
				# UDT instance	
				dataTypeId = dataType
				if udtPrefix != None and udtPrefix != "":
					dataTypeId = "%s/%s" % (udtPrefix, dataType)
				addToTags.append({
							  "name": tagName,
							  "typeId": dataTypeId,
							  "parameters": {
								"DeviceName": {
								  "dataType": "String",
								  "value": {
									"bindType": "parameter",
									"binding": "{DeviceName}"
								  }
								},
								"TagPrefix": {
								  "dataType": "String",
								  "value": {
									"bindType": "parameter",
									"binding": "{TagPrefix}.%s" % realOpcItemPath
								  }
								},
								"Description": {
								  "dataType": "String",
								  "value": {
									"bindType": "parameter",
									"binding": "{Description}"
								  }
								}
							  },
							  "tagType": "UdtInstance",
							  "tags": getUDTTags(udtPrefix, udts, udtStrings, dataType, udtsNotFound)
							})
	return tags

def getUDTTags(udtPrefix, udts, udtStrings, dataType, udtsNotFound):
	tags = []
	
	if dataType not in udts:
		udts[dataType] = []
		udtsNotFound.append(dataType)
	
	udtTags = udts[dataType]
	for udtTag in udtTags:
		tags.extend(addUDTTag(udtPrefix, udts, udtStrings, udtTag, True, udtsNotFound))
	return tags
	
def addTag(deviceName, udtPrefix, udtStrings, udts, tagItem, comments, opcItemPath, inUDTInstance=False):
	tags = []
	if tagItem["selected"]:
		name = tagItem["name"]
		tagName = name
		description = tagItem["description"]
		dataType = tagItem["dataType"]
		arrayLength = tagItem["arrayLength"]
		
		if opcItemPath == None:
			opcItemPath = name
			commentsDict = comments
		else:
			opcItemPath = "%s.%s" % (opcItemPath, name)
			commentsDict = comments.get(opcItemPath, {})
		
		addToTags = tags
		if arrayLength != None:
			arrayTags = []
			tags.append({
			  "name": tagName,
			  "tagType": "Folder",
			  "tags": arrayTags
			})
			addToTags = arrayTags
			
		for i in range(1 if arrayLength is None else arrayLength):
			if arrayLength != None:
				tagName = "%d" % i
				realOpcItemPath = "%s[%d]" % (opcItemPath, i)
			else:
				realOpcItemPath = opcItemPath
				
			if dataType in DATA_TYPE_MAPPING_IGNITION or dataType in udtStrings:
				if dataType in udtStrings:
					dataType = "STRING"
					
				# Simple data type
				if inUDTInstance:
					addToTags.append({
					  "name": tagName,
					  "tagType": "AtomicTag"
					})
				else:
					addToTags.append({
					  "opcItemPath": "ns\u003d1;s\u003d[%s]%s" % (deviceName, realOpcItemPath),
					  "valueSource": "opc",
					  "dataType": DATA_TYPE_MAPPING_IGNITION[dataType],
					  "name": tagName,
					  "documentation": description,
					  "tagType": "AtomicTag",
					  "opcServer": "Ignition OPC UA Server"
					})
			else:
				# UDT instance
				dataTypeId = dataType
				if udtPrefix != None and udtPrefix != "":
					dataTypeId = "%s/%s" % (udtPrefix, dataType)
				
				udtTagsObj = {}
				if tagName in commentsDict:
					udtComments = commentsDict[tagName]
					if len(udtComments) > 0:
						for udtCommentTagPath in udtComments.keys():
							udtComment = udtComments[udtCommentTagPath]
							
							arrayIndex = None
			
							if udtCommentTagPath.endswith("]"):
								arrayIndex = udtCommentTagPath.split("[")[1][:-1]
								udtCommentTagPath = udtCommentTagPath.split("[")[0]
							
							commentTagParts = udtCommentTagPath.split(".")
							tmpTags = udtTagsObj
							for i in range(len(commentTagParts)):
								commentTagPart = commentTagParts[i]
								if commentTagPart not in tmpTags:
									tmpTags[commentTagPart] = {"comment":None, "tags":{}, "arrayIndex":None, "folder":False}
								
								if arrayIndex != None:
									tmpTags[commentTagPart]["tags"][arrayIndex] = {"comment":None, "tags":{}, "arrayIndex":arrayIndex, "folder":False}
									tmpTags[commentTagPart]["folder"] = True
								
								if (i+1) == len(commentTagParts):
									if arrayIndex != None:
										tmpTags[commentTagPart]["tags"][arrayIndex]["comment"] = udtComment
									else:
										tmpTags[commentTagPart]["comment"] = udtComment
									
								tmpTags = tmpTags[commentTagPart]["tags"]
					
				udtTags = buildUDTTagOverrides(dataType.upper(), udtTagsObj, udts, udtStrings)			
				addToTags.append({
							  "name": tagName,
							  "typeId": dataTypeId,
							  "parameters": {
								"DeviceName": {
								  "dataType": "String",
								  "value": {
									"bindType": "parameter",
									"binding": deviceName
								  }
								},
								"TagPrefix": {
								  "dataType": "String",
								  "value": {
									"bindType": "parameter",
									"binding": realOpcItemPath
								  }
								},
								"Description": {
								  "dataType": "String",
								  "value": {
									"bindType": "parameter",
									"binding": description
								  }
								}
							  },
							  "tagType": "UdtInstance",
							  "tags": udtTags
							})
				
	return tags

def buildUDTTagOverrides(udtName, udtTagsObj, udts, udtStrings):
	ret = []
	
	if len(udtTagsObj):
		for tagName in udtTagsObj.keys():
			comment = udtTagsObj[tagName]["comment"]
			arrayIndex = udtTagsObj[tagName]["arrayIndex"]
			isFolder = udtTagsObj[tagName]["folder"]
			childrenTags = udtTagsObj[tagName]["tags"]
			dataType = udts[udtName]["tags"][tagName]["dataType"].upper() if arrayIndex == None else udtName
			
			if dataType in DATA_TYPE_MAPPING_IGNITION or dataType in udtStrings:
				childrenTags = {}
				isUDT = False
			else:
				isUDT = True
			
			if len(childrenTags) == 0:
				if arrayIndex != None:
					actualTagName = arrayIndex
				else:
					actualTagName = udts[udtName]["tags"][tagName]["name"]
			else:
				actualTagName = udts[udtName]["tags"][tagName]["name"]
			
			tagObj = {
				"name": actualTagName,
				"tagType":"Folder" if isFolder else ("UdtInstance" if isUDT else "AtomicTag"),
			}
			
			if comment != None:
				tagObj["documentation"] = comment
			
			if len(childrenTags):
				tagObj["tags"] = buildUDTTagOverrides(dataType, childrenTags, udts, udtStrings)
			
			if len(childrenTags) == 0 and comment == None:
				continue
				
			ret.append(tagObj)
	
	return ret			

def generateUDTExport(udtPrefix, udtItems, globalTagItems, programItems):
	# Create a dictionary of all UDT definitions
	udts = {}
	udtStrings = []
	for udtItem in udtItems:
		name = udtItem.header.content.text
		stringFamily = udtItem.header.content.stringFamily
		tags = udtItem.body.viewParams.tags
		if stringFamily:
			udtStrings.append(name)
		else:
			udts[name] = tags
		
	udtsNotFound = []
	
	# Find all unknown UDTs in global and program tags
	for tagItem in globalTagItems:
		dataType = tagItem["dataType"]
		if not (dataType in DATA_TYPE_MAPPING_IGNITION or dataType in udtStrings):
			if dataType not in udts:
				udts[dataType] = []
				udtsNotFound.append(dataType)
				
	for programItem in programItems:
		programName = programItem.header.content.text
		programTags = programItem.body.viewParams.tags
		
		for tagItem in programTags:
			dataType = tagItem["dataType"]
			if not (dataType in DATA_TYPE_MAPPING_IGNITION or dataType in udtStrings):
				if dataType not in udts:
					udts[dataType] = []
					udtsNotFound.append(dataType)

	# Go through all of the UDT definitions
	returnUDTs = []
	for udtItem in udtItems:
		name = udtItem.header.content.text
		stringFamily = udtItem.header.content.stringFamily
		description = udtItem.header.content.description
		tagItems = udtItem.body.viewParams.tags
		
		if not stringFamily:
			tags = []
			for tagItem in tagItems:
				tags.extend(addUDTTag(udtPrefix, udts, udtStrings, tagItem, False, udtsNotFound))
	
			returnUDTs.append({
				  "name": name,
				  "parameters": {
					"DeviceName": {
					  "dataType": "String"
					},
					"TagPrefix": {
					  "dataType": "String"
					},
					"Description": {
					  "dataType": "String",
					  "value": description
					}
				  },
				  "tagType": "UdtType",
				  "tags": tags
				})
	
	for udt in udtsNotFound:
		returnUDTs.append({
				  "name": udt,
				  "parameters": {
					"DeviceName": {
					  "dataType": "String"
					},
					"TagPrefix": {
					  "dataType": "String"
					},
					"Description": {
					  "dataType": "String",
					  "value": "Built-in?"
					}
				  },
				  "tagType": "UdtType",
				  "tags": []
				})
			
		
	if udtPrefix != None and udtPrefix != "":
		json = {
			"name": udtPrefix,
			"tagType": "Folder",
			"tags": returnUDTs
		}
	else:
		json = {"tags":returnUDTs}
	
	return system.util.jsonEncode(json).replace("\\\\", "\\")
	
def generateTagExport(deviceName, udtPrefix, udtItems, globalTagItems, programItems, comments):
	# Get all of the string family UDTs
	udts = {}
	udtStrings = []
	for udtItem in udtItems:
		name = udtItem.header.content.text
		stringFamily = udtItem.header.content.stringFamily
		tags = udtItem.body.viewParams.tags
		if stringFamily:
			udtStrings.append(name)
		else:
			udtTags = {}
			for tag in tags:
				udtTags[tag["name"].upper()] = {"name":tag["name"], "dataType":tag["dataType"]}
			udts[name.upper()] = {"name":name, "tags":udtTags}

	returnTags = []
	# First go through all of the global tags
	for tagItem in globalTagItems:
		returnTags.extend(addTag(deviceName, udtPrefix, udtStrings, udts, tagItem, comments, None))
				
	# Now go through all of the program tags
	for programItem in programItems:
		programName = programItem.header.content.text
		programTags = programItem.body.viewParams.tags
		
		for tagItem in programTags:
			returnTags.append({
							  "name": "Program " + programName,
							  "tagType": "Folder",
							  "tags": addTag(deviceName, udtPrefix, udtStrings, udts, tagItem, comments, programName)
							})
			
	json = {"tags":returnTags}
	return system.util.jsonEncode(json).replace("\\\\", "\\")