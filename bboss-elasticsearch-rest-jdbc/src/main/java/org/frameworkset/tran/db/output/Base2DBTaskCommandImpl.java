package org.frameworkset.tran.db.output;
/**
 * Copyright 2008 biaoping.yin
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.frameworkset.common.poolman.DBUtil;
import com.frameworkset.common.poolman.NestedSQLException;
import com.frameworkset.common.poolman.StatementInfo;
import org.frameworkset.elasticsearch.ElasticSearchException;
import org.frameworkset.tran.Param;
import org.frameworkset.tran.context.ImportContext;
import org.frameworkset.tran.db.DBRecord;
import org.frameworkset.tran.metrics.ImportCount;
import org.frameworkset.tran.task.BaseTaskCommand;
import org.frameworkset.tran.task.TaskFailedException;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * <p>Description: import datas to database task command</p>
 * <p></p>
 * <p>Copyright (c) 2018</p>
 * @Date 2019/3/1 11:32
 * @author biaoping.yin
 * @version 1.0
 */
public class Base2DBTaskCommandImpl extends BaseTaskCommand<List<DBRecord>, String> {
	private DBOutPutContext es2DBContext;
	private String taskInfo;
	private boolean needBatch;
	public Base2DBTaskCommandImpl(ImportCount importCount, ImportContext importContext,
								  List<DBRecord> datas, int taskNo, String jobNo,String taskInfo,boolean needBatch) {
		super(importCount,importContext,datas.size(),  taskNo,  jobNo);
		this.needBatch = needBatch;
		this.importContext = importContext;
		this.datas = datas;
		es2DBContext = (DBOutPutContext )importContext;
		this.taskInfo = taskInfo;
	}








	public List<DBRecord> getDatas() {
		return datas;
	}


	private List<DBRecord> datas;
	private int tryCount;



	public void setDatas(List<DBRecord> datas) {
		this.datas = datas;
	}

	private void debugDB(String name){
		DBUtil.debugStatus(name);

//		java.util.List<AbandonedTraceExt> traceobjects = DBUtil.getGoodTraceObjects(name);
//		for(int i = 0; traceobjects != null && i < traceobjects.size() ; i ++){
//			AbandonedTraceExt abandonedTraceExt = traceobjects.get(i);
//			if(abandonedTraceExt.getStackInfo() != null)
//				logger.info(abandonedTraceExt.getStackInfo());
//		}
//		logger.info("{}",traceobjects);
	}
	public String execute(){
		String data = null;
		if(this.importContext.getMaxRetry() > 0){
			if(this.tryCount >= this.importContext.getMaxRetry())
				throw new TaskFailedException("task execute failed:reached max retry times "+this.importContext.getMaxRetry());
		}
		this.tryCount ++;
		long start = System.currentTimeMillis();

		StatementInfo stmtInfo = null;
		PreparedStatement statement = null;
		PreparedStatement updateStatement = null;
		PreparedStatement deleteStatement = null;
		TranSQLInfo insertSqlinfo = es2DBContext.getTargetSqlInfo();
		TranSQLInfo updateSqlinfo = es2DBContext.getTargetUpdateSqlInfo();
		TranSQLInfo deleteSqlinfo = es2DBContext.getTargetDeleteSqlInfo();
		Connection con_ = null;
		int batchsize = importContext.getStoreBatchSize();
		try {

//		GetCUDResult CUDResult = null;
			String dbname = es2DBContext.getTargetDBConfig().getDbName();
//			logger.info("DBUtil.getConection(dbname)");
//			debugDB(dbname);
			con_ = DBUtil.getConection(dbname);
			stmtInfo = new StatementInfo(dbname,
					null,
					false,
					con_,
					false);
			stmtInfo.init();

			String oldSql = null;

			String sql = null;
			if(batchsize <= 1 || !needBatch) {//如果batchsize被设置为0或者1直接一次性批处理所有记录
				for(DBRecord record:datas){
					if(record.isInsert()) {
						sql = insertSqlinfo.getSql();
					}
					else if(record.isUpate()){
						sql = updateSqlinfo.getSql();
					}
					else{
						sql = deleteSqlinfo.getSql();
					}

					if(oldSql == null){

						oldSql = sql;
						statement = stmtInfo
								.prepareStatement(sql);
					}
					else if(!oldSql.equals(sql)){
						try {
							statement.executeBatch();
						}
						catch (Exception e){

						}
						finally {
							try {
								statement.close();
							}
							catch (Exception e){

							}

						}
						oldSql = sql;
						statement = stmtInfo
								.prepareStatement(sql);
					}


					for(int i = 0;i < record.size(); i ++)
					{
						Param param = record.get(i);
						statement.setObject(param.getIndex(),param.getValue());
					}
					try {
						statement.addBatch();
					}
					catch (SQLException e){
						throw new NestedSQLException(record.toString(),e);
					}
				}
				if(statement != null) {
					statement.executeBatch();
				}
			}
			else
			{
				int point = batchsize - 1;
				int count = 0;
				for(DBRecord record:datas) {
					if(record.isInsert()) {
						sql = insertSqlinfo.getSql();
					}
					else if(record.isUpate()){
						sql = updateSqlinfo.getSql();
					}
					else{
						sql = deleteSqlinfo.getSql();
					}
					if(oldSql == null){

						oldSql = sql;
						statement = stmtInfo
								.prepareStatement(sql);
					}
					else if(!oldSql.equals(sql)){

						try {
							if(count > 0)
								statement.executeBatch();
						}
						catch (Exception e){

						}
						finally {
							try {
								statement.close();
							}
							catch (Exception e){

							}

						}
						count = 0;
						oldSql = sql;
						statement = stmtInfo
								.prepareStatement(sql);
					}
					for (int i = 0; i < record.size(); i++) {
						Param param = record.get(i);
						statement.setObject(param.getIndex(), param.getValue());
					}
					statement.addBatch();
					if ((count > 0 && count % point == 0)) {
						statement.executeBatch();
						statement.clearBatch();
						count = 0;
						continue;
					}
					count++;
				}
				if(count > 0)
					statement.executeBatch();
			}

		}
		catch(BatchUpdateException error)
		{
			if(stmtInfo != null) {
				try {
					stmtInfo.errorHandle(error);
				} catch (SQLException ex) {
					throw new ElasticSearchException(taskInfo,error);
				}
			}
			throw new ElasticSearchException(taskInfo,error);
		}
		catch (Exception e) {
			if(stmtInfo != null) {

				try {
					stmtInfo.errorHandle(e);
				} catch (SQLException ex) {
					throw new ElasticSearchException(taskInfo,e);
				}
			}
			throw new ElasticSearchException(taskInfo,e);

		} finally {
			if(stmtInfo != null)
				stmtInfo.dofinally();
			if(con_ != null){
				try {
					con_.close();
				}
				catch (Exception e){

				}
			}
//			logger.info("stmtInfo.dofinally()");
//			debugDB(importContext.getDbConfig().getDbName());
			stmtInfo = null;


		}
		return data;
	}

	public int getTryCount() {
		return tryCount;
	}


}
