var ctic_version = "8.0.1";
/*
************************************************
 * ctic.js
 * For common utility functions
 * Note: Do not put any comments as the starting line
************************************************
*/

//--- Checking validity of a results received after a <receive> -----------
function isCTICResult ( lastMessage )
{
	var strOut = "false";
	
	if( lastMessage != undefined ) {
	
		// 1. application.lastmessage$.sourcetype == "sip"
		if( lastMessage.sourcetype != undefined && lastMessage.sourcetype.indexOf("sip:") >= 0 ) {
			
		// 2. application.lastmessage$.contenttype == "application/x-www-form-urlencoded;charset=utf-8"
			if(lastMessage.contenttype != undefined && lastMessage.contenttype.indexOf("application/x-www-form-urlencoded;charset=utf-8") >= 0) {
				strOut = "true";
			}
		}
	}
	
	return strOut;
}

//--- Determine if this is a CTIC call from X-Genesys-GVP-Session-ID -----------
function isCTICCall ( strUserData )
{
	// X-Genesys-GVP-Session-ID: 800DB7A5-33C6-45CB-7E9F-410507B7097A;gvp.rm.datanodes=1;gvp.rm.cti-call=1
	if ( strUserData != undefined) {
		if ( strUserData.indexOf( "gvp.rm.cti-call=1" ) >= 0 ) {
			return "true";
		}
	}

	return "false";
}

//--- Retrieve keys from the associative array containing CTIC returned results -----------
function utilGetReceiveDataValue( strKeyName, arrReceiveData )
{
	var strOut = "";
            
	if ( arrReceiveData != undefined && strKeyName != undefined ) {
		strOut = decodeURI( arrReceiveData[ decodeURI( strKeyName ) ] );
		if (strKeyName == "Result") {
			strOut = strOut.toUpperCase();
		}
	}
	else {
		strOut = undefined;
	}  
	return strOut;
}

function getUserDataArray( dataMessage )
{
	if(dataMessage == undefined)
		return undefined;
	
	var resultArray = new Array();
	var message = dataMessage.split("&");

	var startIndex = 0;
	var endIndex = 0;

	var keyName;
	var keyValue;
	var arrayIndex;
	
	for (arrayIndex in message)	
	{
		startIndex = message[arrayIndex].indexOf ("=", startIndex) + 1;
		endIndex = message[arrayIndex].length;
		
		keyName = message[arrayIndex].substring (0, startIndex-1);
		keyValue = message[arrayIndex].substring (startIndex, endIndex);

		if(keyValue != undefined) {
			resultArray[keyName] = decodeURI("" + keyValue);
		}
		
		startIndex = endIndex = 0;
	}
	return resultArray;
}
