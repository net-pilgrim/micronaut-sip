var common_version = "8.0";
/*
************************************************
 * common.js
 * For common utility functions
 * Note: Do not put any comments as the starting line
************************************************
*/

// Check is the value is a URI
function isURIResource(value)
{
    var isURI = false;
    
    if (value.length == 0)
    	return false;
   
   value = value.toLowerCase();
   
   if (value.match("^http://*|^file://*")) 
   		isURI = true;
   else if (value.indexOf(".jsp") != -1)
   		isURI = true;
   else if (value.substring(0,1) == "." || value.substring(0, 2) == "..")
   		isURI = true;

   return isURI;
}

function getGrammarURI(value)
{
    var grammarURI = "";
    
    if (isURIResource(value))
    	grammarURI = encodeURI(value);
    else
    	grammarURI = AppState.GRAMMARFILEDIR + '/' + AppState.APP_ASR_LANGUAGE + '/' + value;
    
    return grammarURI;
}

function getGBuilderGrammarURI(value, isDTMF)
{
	if ( value == undefined || (value.indexOf("/") == -1 && value.indexOf("\\") == -1 ) ) {
		return value;
	}
	
	var	nSlashPos		= -1;
	var strGrammarURI	= "";
	var strGrammarFile	= "";
		
	nSlashPos = value.lastIndexOf( "/" );
	
	strGrammarFile = value.substr( nSlashPos, value.length - nSlashPos - 1 );
	strGrammarFile = strGrammarFile.substr( 0, strGrammarFile.lastIndexOf(".") );
	strGrammarFile += ".grxml";
		
	// pick up the grxml file name from the input value. GRXML files are expected to be in the 
	// "Resources/Grammars/<language> folder only
	strGrammarURI = AppState.GRAMMARFILEDIR + "/" + AppState.APP_ASR_LANGUAGE + "/" + strGrammarFile;
	strGrammarURI = strGrammarURI.replace( "//", "/" );
	
    if ( isDTMF == true ) {
    	strGrammarURI = strGrammarURI.replace( '/' + AppState.APP_LANGUAGE + '/', '/' + "DTMF" + '/');
    }
    
    return strGrammarURI;
}


function getAudioURI(value)
{
    var audioURI = "";
    
    if (isURIResource(value))
    	audioURI = value;
    else
    	audioURI = AppState.VOXFILEDIR + '/' + AppState.APP_LANGUAGE + '/' + value;
    
    return audioURI;
}

function checkIsObject(result) 
{
	if(result instanceof Object){
		return true;
	}
	return false;
}

function getDNIS()
{

	var address = "";
	
	// OCN takes precedence if available
	var ocn = session.connection.redirect[0];
	if (typeof ocn != 'undefined' && ocn != null) {
		address = ocn.uri;
	} 
	
	if (address == "") {
		address = session.connection.local.uri;
	}

	return getUserPart(address);
}

function getANI()
{

	var address = "";
	
	// this header takes precedence if available
	var pai = session.connection.protocol.sip.headers['p-asserted-identity'];
	if (typeof pai != 'undefined' && pai != null) {
		address = pai;
	} 
	
	if (address == "") {
		address = session.connection.remote.uri;
	}

	return getUserPart(address);
}

function getUserPart(address)
{
	var re = new RegExp("(?:sip|sips|tel):([^@]+)@.*");
	var result = re.exec(address);
	
	if (result == null || result.length < 2) return "";
	
	return result[1];

}
