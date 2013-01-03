package com.nice.zoocache
/**
 * Copyright (C) 2012 NICE Systems ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Arnon Rotem-Gal-Oz
 * @version %I%, %G%
 *          <p/>
 */

import com.netflix.curator.retry.ExponentialBackoffRetry

import org.msgpack.ScalaMessagePack._
import collection.JavaConversions._
import org.apache.zookeeper.{WatchedEvent, Watcher}
import com.netflix.curator.framework
import framework.{CuratorFramework, CuratorFrameworkFactory}
import grizzled.slf4j.Logging
import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.dispatch.Await
import akka.util.duration._
import akka.util.{Duration, Timeout}

/**
 * User: arnonrgo
 * Date: 12/26/12
 * Time: 10:47 AM
 */

//todo: add scavenger to clean ZooCache (cluster of scavengers on all connected clients with leader election)
//todo: API to retrieve Metadata only
//todo: renew zooKeeper connection after it failed on a new access
//todo: change ZooCahce interface to return Future instead of Option (possibly unite java and scala interfaces)
//todo: api to invalidate specific items
//todo: consider replacing curator with util-zk (at least for the simple access stuff) ??
//todo: add ACL support in the API
//todo: add multitenancy support
//todo move scavenger interval setting to the scavenger so it can be synchronized across the system



object ZooCache  {
    val FOREVER : Long= -2
    private[zoocache] val TTL_PATH = "/ttl"
    private val CACHE_ID = "cache"
    private[zoocache] val CACHE_ROOT = "/"+CACHE_ID
    private val INVALIDATE_PATH=CACHE_ROOT+"/invalidate"
}

class ZooCache(connectionString: String,systemId : String, private val localCacheSize: Int =1,private val interval : Duration = 30 minutes) extends ZCache with Logging {

  private val useLocalShadow = localCacheSize>1
  private val retryPolicy = new ExponentialBackoffRetry(1000, 10)
  private val system=ActorSystem(systemId)
  private val unifiedClient = CuratorFrameworkFactory.builder().
    connectString(connectionString).
    namespace(ZooCache.CACHE_ROOT).
    retryPolicy(retryPolicy).
    build
  unifiedClient.start()

  initScavenger


  lazy private val cacheSize=if (localCacheSize>=(Int.MaxValue/2)) Int.MaxValue else localCacheSize*2
  lazy private val shadowActor=system.actorOf(Props(new LocalShadow(cacheSize)))
  if (useLocalShadow) {

    ensurePath(unifiedClient,systemInvalidationPath)
    unifiedClient.getChildren.usingWatcher(watcher).forPath(systemInvalidationPath)
  }
  lazy private val systemInvalidationPath=ZooCache.INVALIDATE_PATH +"/"+systemId
  private val basePath=ZooCache.CACHE_ROOT+"/"+systemId
  implicit val timeout = Timeout(1 second)
  lazy private val watcher : Watcher = new Watcher() {
    override def process(event: WatchedEvent) {
      try {
        //reset the watch as they are one-time
        unifiedClient.getChildren.usingWatcher(watcher).forPath(systemInvalidationPath)
        //shadow.clear()
        shadowActor ! Clear()
      } catch {
        case e: InterruptedException =>  error("problem processing invalidation event",e)
      }
    }
  }

  def initScavenger {

    val scavenger=system.actorOf(Props(new Scavenger(unifiedClient)))
    val sched=system.scheduler.schedule(0 seconds,interval, scavenger, Tick)
  }

  private def buildClients {
    debug("(re)building clients")


  }

  private def ensurePath(cl:CuratorFramework, path:String) {
    val ensurePath = cl.newNamespaceAwareEnsurePath(path)
    ensurePath.ensure(cl.getZookeeperClient)
  }

  //todo:add invalidate by id
  def invalidate(){
    if (unifiedClient.checkExists.forPath(systemInvalidationPath+"/doit")==null)
      unifiedClient.create().forPath(systemInvalidationPath+"/doit")
    else
      unifiedClient.delete().forPath(systemInvalidationPath+"/doit")
  }

  def doesExist(key : String) : Boolean =  if (unifiedClient.checkExists().forPath(basePath+key)!=null) true else false


  def removeAll(parentKey: String) {
    val path =basePath+parentKey
    val children=unifiedClient.getChildren.forPath(path)


    for (child <- children) {
      for(grandchild <-unifiedClient.getChildren.forPath(path+"/"+child)) unifiedClient.delete().forPath(path+"/"+child+"/"+grandchild)
      unifiedClient.delete().forPath(path+"/"+child)
    }
    unifiedClient.delete().inBackground().forPath(path)
  }



  private[zoocache] def putBytes (key : String, input :Array[Byte],ttl: Array[Byte]):Boolean  ={
    val path=basePath+key
    val ttlPath=path+ZooCache.TTL_PATH

    try {

      ensurePath(unifiedClient,path)
      ensurePath(unifiedClient,ttlPath)

      unifiedClient.inTransaction().
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
    val path=basePath+key
    try {
      if (unifiedClient.checkExists().forPath(path) == null) None
      else
        Some(unifiedClient.getData.forPath(path))
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
    shadowActor ! Update(key,input)
    shadowActor ! Update(key + ZooCache.TTL_PATH, meta)
  }

  def get[T<:AnyRef](key: String)(implicit manifest : Manifest[T]):Option[T] = {

    def isInShadow:Boolean ={
      if (!useLocalShadow) return false
      val reply=Await.result(shadowActor ? Get(key+ZooCache.TTL_PATH), 1 second).asInstanceOf[Option[ItemMetadata]]
     reply match
     {
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

    if (isInShadow) return  Await.result(shadowActor ? Get(key), 1 second).asInstanceOf[Option[T]]

    isInCache match{
      case None => None
      case Some(meta) => {
        val result=getData
        if (useLocalShadow) putLocalCopy(key,result.get,meta)
        result
      }
    }
  }

  def get[T<:AnyRef](parentKey: String,key: String)(implicit manifest : Manifest[T]):Option[T] = {
    get[T](parentKey+"/"+key)
  }

  def put(parentKey:String,key :String, input : Any):Boolean ={
    put(parentKey+"/"+key,input)
  }
  def put(parentKey:String,key :String, input : Any, ttl :Long):Boolean ={
    put(parentKey+"/"+key,input,ttl)
  }

  def removeItem(key: String) {
     remove(basePath+key)

    def remove(path :String){
      val children=unifiedClient.getChildren.forPath(path)

      for (child <- children) {
         removeItem(key+"/"+child)
       }
      unifiedClient.delete().forPath(path)
      shadowActor ! Remove(key)
  }

  }

  def shutdown(){
    system.shutdown()
    unifiedClient.close()
  }
}
