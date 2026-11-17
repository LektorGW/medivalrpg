// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import com.google.gson.Gson;
import java.sql.*;
import java.util.*;

/** Worker-confined durable outbox. Reservation before REST means uncertain sends are never duplicated. */
final class ChronicleJournal implements AutoCloseable {
    private final Connection db;
    private final Gson gson=new Gson();
    ChronicleJournal(String url)throws Exception {
        db=DriverManager.getConnection(url);
        try(var s=db.createStatement()){
            s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS chronicle_facts(id TEXT PRIMARY KEY, at INTEGER NOT NULL, payload TEXT NOT NULL, state INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS chronicle_pending ON chronicle_facts(state,at)");
            s.execute("CREATE TABLE IF NOT EXISTS chronicle_attempts(at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS chronicle_reports(cutoff INTEGER PRIMARY KEY, payload TEXT NOT NULL, state INTEGER NOT NULL DEFAULT 0)");
        }
    }
    boolean add(Chronicle.Fact fact)throws Exception {
        if(pendingCount()>=10000)throw new IllegalStateException("Kolejka kroniki pełna");
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO chronicle_facts(id,at,payload) VALUES(?,?,?)")){
            s.setString(1,fact.id());s.setLong(2,fact.at());s.setString(3,gson.toJson(fact));return s.executeUpdate()==1;
        }
    }
    int pendingCount()throws Exception {try(var s=db.createStatement();var r=s.executeQuery("SELECT count(*) FROM chronicle_facts WHERE state=0")){r.next();return r.getInt(1);}}
    List<Chronicle.Fact> between(long from,long to)throws Exception {
        var out=new ArrayList<Chronicle.Fact>();try(var s=db.prepareStatement("SELECT payload FROM chronicle_facts WHERE at>=? AND at<? ORDER BY at,id")){
            s.setLong(1,from);s.setLong(2,to);try(var r=s.executeQuery()){while(r.next())out.add(gson.fromJson(r.getString(1),Chronicle.Fact.class));}
        }return out;
    }
    List<Chronicle.Fact> batch(long now)throws Exception {
        // One complete 5-minute bucket, never a flood of individual NPC skirmishes.
        long oldest;try(var s=db.createStatement();var r=s.executeQuery("SELECT min(at) FROM chronicle_facts WHERE state=0")){r.next();oldest=r.getLong(1);if(r.wasNull()||oldest+300000>now)return List.of();}
        var out=new ArrayList<Chronicle.Fact>();try(var s=db.prepareStatement("SELECT payload FROM chronicle_facts WHERE state=0 AND at<? ORDER BY at,id LIMIT 500")){
            s.setLong(1,oldest+300000);try(var r=s.executeQuery()){while(r.next())out.add(gson.fromJson(r.getString(1),Chronicle.Fact.class));}
        }return out;
    }
    boolean report(long cutoff,Chronicle.Embed embed)throws Exception {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO chronicle_reports(cutoff,payload) VALUES(?,?)")){s.setLong(1,cutoff);s.setString(2,gson.toJson(embed));return s.executeUpdate()==1;}
    }
    Map.Entry<Long,Chronicle.Embed> pendingReport()throws Exception {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT cutoff,payload FROM chronicle_reports WHERE state=0 ORDER BY cutoff LIMIT 1")){
            return r.next()?Map.entry(r.getLong(1),gson.fromJson(r.getString(2),Chronicle.Embed.class)):null;
        }
    }
    boolean reserve(List<Chronicle.Fact> batch,Long report,long now)throws Exception {
        if(report==null&&batch.isEmpty())return false;
        db.setAutoCommit(false);
        try {
            try(var s=db.prepareStatement("SELECT count(*) FROM chronicle_attempts WHERE at>?")){s.setLong(1,now-3600000);try(var r=s.executeQuery()){if(r.next()&&r.getInt(1)>=20){db.rollback();return false;}}}
            if(report!=null){try(var s=db.prepareStatement("UPDATE chronicle_reports SET state=1 WHERE cutoff=? AND state=0")){s.setLong(1,report);if(s.executeUpdate()!=1){db.rollback();return false;}}}
            else for(var f:batch)try(var s=db.prepareStatement("UPDATE chronicle_facts SET state=1 WHERE id=? AND state=0")){s.setString(1,f.id());if(s.executeUpdate()!=1){db.rollback();return false;}}
            try(var s=db.prepareStatement("INSERT INTO chronicle_attempts VALUES(?)")){s.setLong(1,now);s.executeUpdate();}
            db.commit();return true;
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    void delivered(List<Chronicle.Fact> batch,Long report)throws Exception {
        if(report!=null){try(var s=db.prepareStatement("UPDATE chronicle_reports SET state=2 WHERE cutoff=?")){s.setLong(1,report);s.executeUpdate();}}
        else for(var f:batch)try(var s=db.prepareStatement("UPDATE chronicle_facts SET state=2 WHERE id=?")){s.setString(1,f.id());s.executeUpdate();}
    }
    void prune(long now)throws Exception {
        try(var s=db.prepareStatement("DELETE FROM chronicle_facts WHERE at<? AND state<>0")){s.setLong(1,now-14L*86400000);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM chronicle_attempts WHERE at<=?")){s.setLong(1,now-3600000);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM chronicle_reports WHERE cutoff<? AND state<>0")){s.setLong(1,now-14L*86400000);s.executeUpdate();}
    }
    public void close()throws SQLException{db.close();}
}
