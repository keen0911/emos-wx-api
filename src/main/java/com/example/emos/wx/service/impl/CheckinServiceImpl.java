package com.example.emos.wx.service.impl;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateRange;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.baidu.aip.face.AipFace;
import com.baidu.aip.util.Base64Util;
import com.example.emos.wx.config.BaiduAipFace;
import com.example.emos.wx.config.SystemConstants;
import com.example.emos.wx.db.dao.*;
import com.example.emos.wx.db.pojo.TbCheckin;
import com.example.emos.wx.db.pojo.TbFaceModel;
import com.example.emos.wx.exception.EmosException;
import com.example.emos.wx.service.CheckinService;
import com.example.emos.wx.task.EmailTask;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

@Service
@Scope("prototype")
@Slf4j
public class CheckinServiceImpl implements CheckinService {
    @Autowired
    private SystemConstants constants;

    @Autowired
    private TbHolidaysDao holidaysDao;

    @Autowired
    private TbWorkdayDao workdayDao;

    @Autowired
    private TbCheckinDao checkinDao;

    @Autowired
    private BaiduAipFace aipface;

    @Autowired
    private TbCityDao cityDao;

    @Autowired
    private TbUserDao userDao;

    @Autowired
    private EmailTask emailTask;

    @Value("${emos.email.hr}")
    private String hrEmail;

    @Autowired
    private TbFaceModelDao faceModelDao;





    @Override
    public String validCanCheckIn(int userId, String date) {
        boolean bool_1 = holidaysDao.searchTodayIsHolidays() != null;
        boolean bool_2 = workdayDao.searchTodayIsWorkday() != null;
        String type = "?????????";
        if (DateUtil.date().isWeekend()) {
            type = "?????????";
        }
        if (bool_1) {
            type = "?????????";
        } else if (bool_2) {
            type = "?????????";
        }
        if (type.equals("?????????")) {
            return "????????????????????????";
        } else {
            DateTime now = DateUtil.date();
            String start = DateUtil.today() + " " + constants.attendanceStartTime;
            String end = DateUtil.today() + " " + constants.attendanceEndTime;
            DateTime attendanceStart = DateUtil.parse(start);
            DateTime attendanceEnd = DateUtil.parse(end);
            if (now.isBefore(attendanceStart)) {
                return "??????????????????????????????";
            } else if (now.isAfter(attendanceEnd)) {
                return "???????????????????????????";
            } else {
                HashMap map = new HashMap();
                map.put("userId", userId);
                map.put("date", date);
                map.put("start", start);
                map.put("end", end);
                boolean bool = checkinDao.haveCheckin(map) != null;
                return bool ? "???????????????????????????????????????" : "????????????";
            }
        }
    }

    @Override
    public void checkin(HashMap param) {
        Date d1=DateUtil.date();
        Date d2=DateUtil.parse(DateUtil.today()+" "+constants.attendanceTime);
        Date d3=DateUtil.parse(DateUtil.today()+" "+constants.attendanceEndTime);
        int status=1;

        int userId= (Integer) param.get("userId");
        String faceModel=faceModelDao.searchFaceModel(userId);
        if(faceModel==null){
            throw new EmosException("?????????????????????");
        }
        if(d1.compareTo(d2)<=0){
            status=1;
        }
        else if(d1.compareTo(d2)>0&&d1.compareTo(d3)<0){
            status=2;
        }
        else{
            throw new EmosException("????????????????????????????????????");
        }

        AipFace client = aipface.GetClient();
        String image=(String)param.get("path");

        String imgFile="D:\\picture\\123456.jpg";

        InputStream in = null;
        byte[] data = null;
        try {
            in = new FileInputStream(image);
            data = new byte[in.available()];
            in.read(data);
            in.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        String image64= Base64Util.encode(data);


        String imageType = "BASE64";//???base64????????????
        String groupIdList = "emos_face";
        Double score;
        HashMap<String, Object> options = new HashMap();
        options.put("match_threshold", "70");
        options.put("quality_control", "NORMAL");
        options.put("liveness_control", "LOW");
        options.put("user_id", userId);
        options.put("max_user_num", "3");
        // ????????????
        JSONObject res = client.search(image64, imageType, groupIdList, options);

        JSONObject r=null;
        try {
            System.out.println(res.toString(2));
            r = res.getJSONObject("result");
        } catch (JSONException e) {
            throw new EmosException("???????????????????????????");
        }

        if(r.isEmpty()){
            throw new EmosException("???????????????????????????");
        }
        else{
            int risk=1;
            String city= (String) param.get("city");
            String district= (String) param.get("district");
            String address= (String) param.get("address");
            String country= (String) param.get("country");
            String province= (String) param.get("province");
            if(!StrUtil.isBlank(city)&&!StrUtil.isBlank(district)){
                String code= cityDao.searchCode(city);
                try{
                    String url = "http://m." + code + ".bendibao.com/news/yqdengji/?qu=" + district;
                    System.out.println(url);
                    Document document= Jsoup.connect(url).get();
                    Elements elements=document.getElementsByClass("list-content");
                    if(elements.size()>0){
                        Element element=elements.get(0);
                        String result=element.select("p:last-child").text();
//                            result="?????????";
                        if("?????????".equals(result)){
                            risk=3;
                            //??????????????????
                            HashMap<String,String> map=userDao.searchNameAndDept(userId);
                            String name = map.get("name");
                            String deptName = map.get("dept_name");
                            deptName = deptName != null ? deptName : "";
                            SimpleMailMessage message=new SimpleMailMessage();
                            message.setTo(hrEmail);
                            message.setSubject("??????" + name + "?????????????????????????????????");
                            message.setText(deptName + "??????" + name + "???" + DateUtil.format(new Date(), "yyyy???MM???dd???") + "??????" + address + "????????????????????????????????????????????????????????????????????????????????????");
                            emailTask.sendAsync(message);
                        }
                        else if("?????????".equals(result)){
                            risk=2;
                        }
                    }
                }catch (Exception e){
                    log.error("????????????",e);
                    throw new EmosException("????????????????????????");
                }
            }
            //??????????????????
            TbCheckin entity=new TbCheckin();
            entity.setUserId(userId);
            entity.setAddress(address);
            entity.setCountry(country);
            entity.setProvince(province);
            entity.setCity(city);
            entity.setDistrict(district);
            entity.setStatus((byte) status);
            entity.setRisk(risk);
            entity.setDate(DateUtil.today());
            entity.setCreateTime(d1);
            checkinDao.insert(entity);
        }
    }
    @Override
    public void createFaceModel(int uId, String path) {

        AipFace client = aipface.GetClient();
        // ??????????????????????????????
        HashMap<String, String> options = new HashMap<>();
        options.put("user_info", "user's info");
        options.put("action_type", "REPLACE");//???????????? APPEND: ???user_id?????????????????????????????????user_id?????????????????????????????????????????????????????????user_id???,REPLACE : ?????????user_id???????????????,??????????????????????????????user_id???????????????,????????????APPEND
/*        String image = "";
        String imageType = "URL";*/
        InputStream in = null;
        byte[] data = null;
        try {
            in = new FileInputStream(path);
            data = new byte[in.available()];
            in.read(data);
            in.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        String image= Base64Util.encode(data);
        String imageType="BASE64";
        String groupId = "emos_face";
        String userId = String.valueOf(uId);
        // ????????????
        JSONObject res = client.addUser(image, imageType, groupId, userId, options);
        try {
            System.out.println(res.toString(2));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if(res.isEmpty()){
            throw new EmosException("????????????????????????");
        }
        else{
            TbFaceModel entity=new TbFaceModel();
            entity.setUserId(uId);
            entity.setFaceModel("1");
            faceModelDao.insert(entity);
        }
    }

    @Override
    public HashMap searchTodayCheckin(int userId) {
        HashMap map=checkinDao.searchTodayCheckin(userId);
        return map;
    }

    @Override
    public long searchCheckinDays(int userId) {
        long days=checkinDao.searchCheckinDays(userId);
        return days;
    }

    @Override
    public ArrayList<HashMap> searchWeekCheckin(HashMap param) {
        ArrayList<HashMap> checkinList=checkinDao.searchWeekCheckin(param);
        ArrayList holidaysList=holidaysDao.searchHolidaysInRange(param);
        ArrayList workdayList=workdayDao.searchWorkdayInRange(param);
        DateTime startDate=DateUtil.parseDate(param.get("startDate").toString());
        DateTime endDate=DateUtil.parseDate(param.get("endDate").toString());
        DateRange range=DateUtil.range(startDate,endDate, DateField.DAY_OF_MONTH);
        ArrayList<HashMap> list=new ArrayList<>();
        range.forEach(one->{
            String date=one.toString("yyyy-MM-dd");
            String type="?????????";
            if(one.isWeekend()){
                type="?????????";
            }
            if(holidaysList!=null&&holidaysList.contains(date)){
                type="?????????";
            }
            else if(workdayList!=null&&workdayList.contains(date)){
                type="?????????";
            }
            String status="";
            if(type.equals("?????????")&&DateUtil.compare(one,DateUtil.date())<=0){
                status="??????";
                boolean flag=false;
                for (HashMap<String,String> map:checkinList){
                    if(map.containsValue(date)){
                        status=map.get("status");
                        flag=true;
                        break;
                    }
                }
                DateTime endTime=DateUtil.parse(DateUtil.today()+" "+constants.attendanceEndTime);
                String today=DateUtil.today();
                if(date.equals(today)&&DateUtil.date().isBefore(endTime)&&flag==false){
                    status="";
                }
            }
            HashMap map=new HashMap();
            map.put("date",date);
            map.put("status",status);
            map.put("type",type);
            map.put("day",one.dayOfWeekEnum().toChinese("???"));
            list.add(map);
        });
        return list;
    }

    @Override
    public ArrayList<HashMap> searchMonthCheckin(HashMap param) {
        return this.searchWeekCheckin(param);
    }

}
