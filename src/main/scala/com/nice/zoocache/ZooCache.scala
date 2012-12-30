package com.nice.zoocache


import com.netflix.curator.retry.ExponentialBackoffRetry

import org.msgpack.ScalaMessagePack._
import collection.JavaConversions._
import org.apache.zookeeper.{WatchedEvent, Watcher}
import java.util.Date
import com.netflix.curator.framework
import framework.{CuratorFramework, CuratorFrameworkFactory}
import grizzled.slf4j.Logging


/**
 * User: arnonrgo
 * Date: 12/26/12
 * Time: 10:47 AM
 */

object ZooCache  {
    val FOREVER : Long= -2
    private val TTL_PATH = "/ttl"
    private val CACHE_ROOT = "/cache/"
    private val MAX_LOCAL_SHADOW_SIZE = 20000
    private val INVALIDATE_PATH="/invalidate"
}
class ZooCache(connectionString: String,systemId : String, useLocalShadow : Boolean = false) extends Logging {

  private val retryPolicy = new ExponentialBackoffRetry(1000, 10)

  private var client : CuratorFramework  = null
  private var localInvalidationClient: CuratorFramework = null

  buildClients()

  lazy private val shadow = new LocalShadow(ZooCache.MAX_LOCAL_SHADOW_SIZE)
  lazy private val systemInvalidationPath=ZooCache.INVALIDATE_PATH +"/"+systemId
  lazy private val watcher : Watcher = new Watcher() {
    override def process(event: WatchedEvent) {
      try {
        //reset the watch
        localInvalidationClient.getChildren.usingWatcher(watcher).forPath(systemInvalidationPath)
        shadow.clear()
      } catch {
        case e: InterruptedException =>  error("problem processing invalidation event",e)
      }
    }
  }

  private def buildClients() {
    debug("(re)building clients")
    client = CuratorFrameworkFactory.builder().
      connectString(connectionString).
      namespace(ZooCache.CACHE_ROOT+systemId).
      retryPolicy(retryPolicy).
      build

    client.start()
    if (useLocalShadow) initLocal()

    def initLocal() {
      localInvalidationClient = CuratorFrameworkFactory.builder().
        connectString(connectionString).
        namespace(ZooCache.INVALIDATE_PATH).
        retryPolicy(retryPolicy).
        build

      localInvalidationClient.start()
      ensurePath(localInvalidationClient,systemInvalidationPath)
      localInvalidationClient.getChildren.usingWatcher(watcher).forPath(systemInvalidationPath)
    }
  }

  private def ensurePath(cl:CuratorFramework, path:String) {
    val ensurePath = cl.newNamespaceAwareEnsurePath(path)
    ensurePath.ensure(cl.getZookeeperClient)
  }

  //todo:add invalidate by id
  def invalidate(){
    if (localInvalidationClient.checkExists.forPath(systemInvalidationPath+"/doit")==null)
      localInvalidationClient.create().forPath(systemInvalidationPath+"/doit")
    else
      localInvalidationClient.delete().forPath(systemInvalidationPath+"/doit")
  }

  def doesExist(key : String) : Boolean =  if (client.checkExists().forPath("/"+key)!=null) true else false


  def removeAll(parentKey: String) {
    val path="/"+parentKey
    val children=client.getChildren.forPath(path)


    //todo: consider making a recursive function
    for (child <- children) {
      for(grandchild <-client.getChildren.forPath(path+"/"+child)) client.delete().forPath(path+"/"+child+"/"+grandchild)
      client.delete().forPath(path+"/"+child)
    }
    client.delete().inBackground().forPath(path)
  }

  private[zoocache] def putBytes (key : String, input :Array[Byte],ttl: Array[Byte]):Boolean  ={
    val path="/"+key
    val ttlPath=path+ZooCache.TTL_PATH

    try {

      ensurePath(client,path)
      ensurePath(client,ttlPath)

      client.inTransaction().
          setData().forPath(path,input).
        and().
          setData().forPath(ttlPath,ttl).
        and().
          commit()

      true
    } catch {
      case e: Exception => {
        error("can't read '"+key+"' from Zookeeper",e)
        false
      }
    }
  }


  private[zoocache] def  getBytes(key:String):Option[Array[Byte]] ={
    val path="/"+key
    try {
      if (client.checkExists().forPath(path) == null) None
      else
        Some(client.getData.forPath(path))
    } catch {
      case e: Exception => {
        error("can't update '"+ key+"' in Zookeeper",e)
        None
      }
    }

  }

  def put(key :String, input : Any, ttl: Long = ZooCache.FOREVER):Boolean ={
    val meta=new ItemMetadata()
    meta.ttl= ttl
    val wasSuccessful=putBytes(key,pack(input),pack(meta))

    if (wasSuccessful && useLocalShadow) {
          putLocalCopy(key, input, meta)
    }

    wasSuccessful
  }


  private def putLocalCopy(key: String, input: Any, meta: ItemMetadata) {
    shadow.update(key, input)
    shadow.update(key + ZooCache.TTL_PATH, meta)
  }

  def get[T](key: String)(implicit manifest : Manifest[T]):Option[T] = {

    def isInShadow:Boolean ={
      if (!useLocalShadow) return false

      shadow.get[ItemMetadata](key+ZooCache.TTL_PATH) match {
        case Some(metadata)=>  metadata.isValid
        case None =>  false
      }
    }

    def isInCache:Option[ItemMetadata] ={
      getBytes(key+ZooCache.TTL_PATH) match {
        case Some(meta) => {
                val result=unpack[ItemMetadata](meta)
                if (result.isValid) Some(result) else None
        }
        case None =>None
        }
    }

    def getData:Option[T]={
      val data = getBytes(key)
      data match {
      case Some(result) =>  Some(unpack[T](result))
      case None => None  // key not found
       }
    }

    if (isInShadow) return shadow.get[T](key)

    isInCache match{
      case None => None
      case Some(meta) => {
        val result=getData
        if (useLocalShadow) putLocalCopy(key,result.get,meta)
        result
      }
    }



  }

  def get[T](parentKey: String,key: String)(implicit manifest : Manifest[T]):Option[T] = {
    get[T](parentKey+"/"+key)
  }

  def put(parentKey:String,key :String, input : Any):Boolean ={
    put(parentKey+"/"+key,input)
  }
  def put(parentKey:String,key :String, input : Any, ttl :Long):Boolean ={
    put(parentKey+"/"+key,input,ttl)
  }


}
