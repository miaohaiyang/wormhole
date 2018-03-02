package edp.mad.rest.response


import akka.http.scaladsl.model.HttpMethods
import com.alibaba.fastjson.JSONObject
import edp.mad.cache._
import edp.mad.elasticsearch.AppInfos
import edp.mad.elasticsearch.MadES.madES
import edp.mad.elasticsearch.MadIndex._
import edp.mad.module.ModuleObj
import edp.mad.util.HttpClient
import edp.wormhole.common.util.DateUtils.{currentyyyyMMddHHmmss, yyyyMMddHHmmssToString}
import edp.wormhole.common.util.{DateUtils, DtFormat, JsonUtils}
import org.apache.log4j.Logger
import org.json4s.{DefaultFormats, Formats, JNothing, JValue}

import scala.collection.mutable.ListBuffer

object YarnRMResponse{
  private val logger = Logger.getLogger(this.getClass)
  implicit val json4sFormats: Formats = DefaultFormats
  val modules = ModuleObj.getModule
  lazy val baseRMSite = getActiveResourceManager( modules.hadoopYarnRMSite1, modules.hadoopYarnRMSite2)
  lazy val baseUrl = s"http://${baseRMSite.stripPrefix ("http://").stripPrefix ("/")}/ws/v1/cluster"

  def getResourceManagerHaState(rm: String): String = {
    val url = s"http://${rm.stripPrefix("http://").stripPrefix("/")}/ws/v1/cluster/info"
    var haStateValue =""
    if (rm != null && rm != "") {
      val response = HttpClient.syncClientGetJValue("", url, HttpMethods.GET, "", "", "")
      if (response._1 == true) {
        try {
          val jObj = JsonUtils.getJValue(response._2, "clusterInfo")
          if (jObj != null && jObj != JNothing) {
            haStateValue = JsonUtils.getString(jObj, "haState")
          }
        }catch {
          case ex: Exception =>
            logger.error(s"query yarn resourceManager $url haState failed", ex)
        }
      }
    }
    haStateValue
  }

  def getActiveResourceManager(rm1: String, rm2: String): String = {
    if (getResourceManagerHaState(rm1).toUpperCase == "ACTIVE")
      rm1
    else if (getResourceManagerHaState(rm2).toUpperCase == "ACTIVE")
      rm2
    else {
      logger.error(s"query yarn resourceManager haState failed caused by both resourceManager url is empty.")
      ""
    }
  }

  def getActiveAppsInfo() = {
   //val url = s"${baseUrl}/apps?states=accepted,running,killed,failed,finished&&startedTimeBegin=$startedTimeBegin&&applicationTypes=spark"
    val madProcessTime = yyyyMMddHHmmssToString(currentyyyyMMddHHmmss, DtFormat.TS_DASH_MILLISEC)
    val url = s"${baseUrl}/apps?states=accepted,running&&applicationTypes=spark"
    val esBulkList = new ListBuffer[String]
    val esSchemaMap = madES.getSchemaMap(INDEXAPPINFOS.toString)
    val response = HttpClient.syncClientGetJValue("", url, HttpMethods.GET, "", "", "")
    if (response._1 == true) {
      try {
        logger.info(s" response body  ${response._2} \n")
        if( JsonUtils.getJValue(response._2, "apps") != null ) {
          val appObjs = JsonUtils.getJValue( JsonUtils.getJValue(response._2, "apps"),"app")
          if( appObjs != null && appObjs != JNothing) {
            appObjs.extract[Array[JValue]].foreach { appObj =>

              val flattenJson = new JSONObject
              esSchemaMap.foreach{e=>
               // logger.info(s" = = = = 0 ${e._1}  ${e._2}")
                e._1 match{
                  case "madProcessTime" =>  flattenJson.put( e._1, DateUtils.dt2string(DateUtils.dt2dateTime(madProcessTime) ,DtFormat.TS_DASH_SEC) )
                  case "appId" =>   flattenJson.put( e._1, JsonUtils.getString(appObj, "id") )
                  case "streamName" =>  flattenJson.put( e._1, JsonUtils.getString(appObj, "name") )
                  case "state" =>  flattenJson.put( e._1, JsonUtils.getString(appObj, "state") )
                  case "finalStatus" => flattenJson.put( e._1,  JsonUtils.getString(appObj, "finalStatus") )
                  case "user" =>  flattenJson.put( e._1, JsonUtils.getString(appObj, "user") )
                  case "queue" =>  flattenJson.put( e._1, JsonUtils.getString(appObj, "queue") )
                  case "startedTime" =>  flattenJson.put( e._1, DateUtils.dt2string(JsonUtils.getLong(appObj, "startedTime") * 1000, DtFormat.TS_DASH_SEC) )
                }
              }
              esBulkList.append(flattenJson.toJSONString)

              modules.applicationMap.set(ApplicationMapKey(JsonUtils.getString(appObj, "id")), ApplicationMapValue(JsonUtils.getString(appObj, "name")))
              logger.debug(s" ===  ${appObj} \n")
            }
          }else{ logger.error(s" failed to get apps/app  \n") }
        }else{ logger.error(s" failed to get apps \n") }
      }catch{
        case e:Exception =>
          logger.error(s"failed to parse response ${response._2} \n",e)
      }
    }else{ logger.error(s"failed to get the response from yarn resource manager ${response}" ) }

    if(esBulkList.nonEmpty){
      val rc = madES.bulkIndex2Es( esBulkList.toList, INDEXAPPINFOS.toString)
      logger.info(s" bulkindex message into ES ${rc}\n")
    }else {
      logger.info(s" the madAppInfo list is empty \n")
    }

    //logger.info(s"  ${modules.applicationMap.mapPrint}")
  }

  def getAllAppsInfo():List[AppInfos] = {
    val madProcessTime = yyyyMMddHHmmssToString(currentyyyyMMddHHmmss, DtFormat.TS_DASH_MILLISEC)
    val bList = new ListBuffer[AppInfos]
    val url = s"${baseUrl}/apps?states=accepted,running,killed,failed,finished&&applicationTypes=spark"
    val response = HttpClient.syncClientGetJValue("", url, HttpMethods.GET, "", "", "")
    if (response._1 == true) {
      try {
        logger.info(s" response body  ${response._2} \n")
        if( JsonUtils.getJValue(response._2, "apps") != null ) {
          val appObjs = JsonUtils.getJValue( JsonUtils.getJValue(response._2, "apps"),"app")
          if( appObjs != null && appObjs != JNothing) {
            appObjs.extract[Array[JValue]].foreach { appObj =>
              logger.debug(s" ===  ${appObj} \n")
              val appId = JsonUtils.getString(appObj, "id")
              val streamName = JsonUtils.getString(appObj, "name")
              val state = JsonUtils.getString(appObj, "state")
              val finalStatus = JsonUtils.getString(appObj, "finalStatus")
              val user = JsonUtils.getString(appObj, "user")
              val queue = JsonUtils.getString(appObj, "queue")
              val startedTime = JsonUtils.getLong(appObj, "startedTime")
              logger.debug(s" Application Map ${appId}   ${streamName}  ${startedTime} \n")
              val appInfos = AppInfos( DateUtils.dt2string(DateUtils.dt2dateTime(madProcessTime) ,DtFormat.TS_DASH_SEC), appId, streamName, state, finalStatus, user, queue,
                DateUtils.dt2string(startedTime*1000, DtFormat.TS_DASH_SEC) )
              bList.append(appInfos)
             }
          }else{ logger.error(s" failed to get apps/app  \n") }
        }else{ logger.error(s" failed to get apps \n") }
      }catch{
        case e:Exception =>
          logger.error(s"failed to parse response ${response._2} \n",e)
      }
    }else{ logger.error(s"failed to get the response from yarn resource manager ${response}" ) }
    logger.info(s"  ${bList.toList}")
    bList.toList
  }


}