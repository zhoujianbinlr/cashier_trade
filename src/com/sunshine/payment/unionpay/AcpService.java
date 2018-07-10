package com.sunshine.payment.unionpay;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

public class AcpService {

	private static Logger logger = LoggerFactory.getLogger(AcpService.class);

	/**
	 * 请求报文签名(使用配置文件中配置的私钥证书加密)<br>
	 * 功能：对请求报文进行签名,并计算赋值certid,signature字段并返回<br>
	 * @param reqData 请求报文map<br>
	 * @param encoding 上送请求报文域encoding字段的值<br>
	 * @return　签名后的map对象<br>
	 */
	public static Map<String, String> sign(Map<String, String> reqData, String encoding, String pfxKeyFile, String keyPwd, String type) {
		Map<String, String> submitData = SDKUtil.filterBlank(reqData);
		SDKUtil.sign(submitData, encoding, pfxKeyFile, keyPwd, type);
		return submitData;
	}

	/**
	 * 多证书签名(通过传入私钥证书路径和密码加密）<br>
	 * 功能：如果有多个商户号接入银联,每个商户号对应不同的证书可以使用此方法:传入私钥证书和密码(并且在acp_sdk.properties中 配置 acpsdk.singleMode=false)<br>
	 * @param reqData 请求报文map<br>
	 * @param certPath 签名私钥文件（带路径）<br>
	 * @param certPwd 签名私钥密码<br>
	 * @param encoding 上送请求报文域encoding字段的值<br>
	 * @return　签名后的map对象<br>
	 */
	public static Map<String, String> sign(Map<String, String> reqData, String certPath, String certPwd, String encoding) {
		Map<String, String> submitData = SDKUtil.filterBlank(reqData);
		logger.info("银联排序后参数:{}", JSON.toJSONString(submitData));
		boolean flag = SDKUtil.signByCertInfo(submitData, certPath, certPwd, encoding);
		if (flag) {
			logger.info("银联签名后参数:{}", JSON.toJSONString(submitData));
		} else {
			logger.info("银联签名失败,订单号:{}", JSON.toJSONString(submitData));
		}

		return submitData;
	}

	/**
	 * 验证签名(SHA-1摘要算法)<br>
	 * @param resData 返回报文数据<br>
	 * @param encoding 上送请求报文域encoding字段的值<br>
	 * @return true 通过 false 未通过<br>
	 */
	public static boolean validate(Map<String, String> rspData, String encoding, String dir) {
		logger.info("验签处理开始,dir:{}", dir);
		if (SDKUtil.isEmpty(encoding)) {
			encoding = "UTF-8";
		}

		String version = rspData.get("version");
		if (UnionPayUtils.VERSION_5_1_0.equals(version)) {
			// 1.从返回报文中获取公钥信息转换成公钥对象
			String strCert = rspData.get(SDKConstants.param_signPubKeyCert);
			//			LogUtil.writeLog("验签公钥证书：["+strCert+"]");
			X509Certificate x509Cert = CertUtil.genCertificateByStr(strCert);
			if (x509Cert == null) {
				logger.info("convert signPubKeyCert failed");
				return false;
			}
			// 2.验证证书链
			if (!CertUtil.verifyCertificate(x509Cert)) {
				logger.info("验证公钥证书失败，证书信息：[" + strCert + "]");
				return false;
			}

			// 3.验签
			String stringSign = rspData.get(SDKConstants.param_signature);
			logger.info("签名原文：[" + stringSign + "]");
			// 将Map信息转换成key1=value1&key2=value2的形式
			String stringData = coverMap2String(rspData);
			logger.info("待验签返回报文串：[" + stringData + "]");
			try {
				// 验证签名需要用银联发给商户的公钥证书.
				boolean result =
						SecureUtil.validateSignBySoft256(x509Cert.getPublicKey(), SecureUtil.base64Decode(stringSign.getBytes(encoding)),
								SecureUtil.sha256X16(stringData, encoding));
				logger.info("验证签名" + ( result ? "成功" : "失败" ));
				return result;
			} catch (UnsupportedEncodingException e) {
				logger.info(e.getMessage(), e);
			} catch (Exception e) {
				logger.info(e.getMessage(), e);
			}
		} else {
			String stringSign = rspData.get(SDKConstants.param_signature);

			// 从返回报文中获取certId ，然后去证书静态Map中查询对应验签证书对象
			String certId = rspData.get(SDKConstants.param_certId);

			logger.info("对返回报文串验签使用的验签公钥序列号：{}", certId);

			// 将Map信息转换成key1=value1&key2=value2的形式
			String stringData = SDKUtil.coverMap2String(rspData);

			logger.info("待验签返回报文串：{}", stringData);

			try {
				// 验证签名需要用银联发给商户的公钥证书.
				return SecureUtil.validateSignBySoft(CertUtil.getValidateKey(certId, dir), SecureUtil.base64Decode(stringSign.getBytes(encoding)),
						SecureUtil.sha1X16(stringData, encoding));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return false;
	}

	/**
	 * 对控件支付成功返回的结果信息中data域进行验签（控件端获取的应答信息）
	 * @param jsonData json格式数据，例如：{"sign" : "J6rPLClQ64szrdXCOtV1ccOMzUmpiOKllp9cseBuRqJ71pBKPPkZ1FallzW18gyP7CvKh1RxfNNJ66AyXNMFJi1OSOsteAAFjF5GZp0Xsfm3LeHaN3j/N7p86k3B1GrSPvSnSw1LqnYuIBmebBkC1OD0Qi7qaYUJosyA1E8Ld8oGRZT5RR2gLGBoiAVraDiz9sci5zwQcLtmfpT5KFk/eTy4+W9SsC0M/2sVj43R9ePENlEvF8UpmZBqakyg5FO8+JMBz3kZ4fwnutI5pWPdYIWdVrloBpOa+N4pzhVRKD4eWJ0CoiD+joMS7+C0aPIEymYFLBNYQCjM0KV7N726LA==",  "data" : "pay_result=success&tn=201602141008032671528&cert_id=68759585097"}
	 * @return 是否成功
	 */
	public static boolean validateAppResponse(String jsonData, String encoding, String dir) {
		logger.info("控件应答信息验签处理开始：{}", jsonData);
		if (SDKUtil.isEmpty(encoding)) {
			encoding = "UTF-8";
		}

		Pattern p = Pattern.compile("\\s*\"sign\"\\s*:\\s*\"([^\"]*)\"\\s*");
		Matcher m = p.matcher(jsonData);
		if (!m.find())
			return false;
		String sign = m.group(1);

		p = Pattern.compile("\\s*\"data\"\\s*:\\s*\"([^\"]*)\"\\s*");
		m = p.matcher(jsonData);
		if (!m.find())
			return false;
		String data = m.group(1);

		p = Pattern.compile("cert_id=(\\d*)");
		m = p.matcher(jsonData);
		if (!m.find())
			return false;
		String certId = m.group(1);

		try {
			// 验证签名需要用银联发给商户的公钥证书.
			return SecureUtil.validateSignBySoft(CertUtil.getValidateKey(certId, dir), SecureUtil.base64Decode(sign.getBytes(encoding)),
					SecureUtil.sha1X16(data, encoding));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * 功能：后台交易提交请求报文并接收同步应答报文<br>
	 * @param reqData 请求报文<br>
	 * @param rspData 应答报文<br>
	 * @param reqUrl  请求地址<br>
	 * @param encoding<br>
	 * @return 应答http 200返回true ,其他false<br>
	 */
	public static Map<String, String> post(Map<String, String> reqData, String reqUrl, String encoding) {
		Map<String, String> rspData = new HashMap<String, String>();
		logger.info("请求银联地址:{}, 请求入参:{}", reqUrl, JSON.toJSONString(reqData));
		//发送后台请求数据
		HttpClient hc = new HttpClient(reqUrl, 30000, 30000);
		try {
			int status = hc.send(reqData, encoding);
			if (200 == status) {
				String resultString = hc.getResult();
				logger.info("请求银联返回结果:{}", resultString);
				if (null != resultString && !"".equals(resultString)) {
					// 将返回结果转换为map
					Map<String, String> tmpRspData = SDKUtil.convertResultStringToMap(resultString);
					rspData.putAll(tmpRspData);
				}
			} else {
				logger.info("返回http状态码:{}，请检查请求报文或者请求地址是否正确", status);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return rspData;
	}

	/**
	 * 功能：http Get方法 便民缴费产品中使用<br>
	 * @param reqUrl
	 * @param encoding
	 * @return
	 */
	public static String get(String reqUrl, String encoding) {

		logger.info("请求银联地址:{}", reqUrl);
		//发送后台请求数据
		HttpClient hc = new HttpClient(reqUrl, 30000, 30000);
		try {
			int status = hc.sendGet(encoding);
			if (200 == status) {
				String resultString = hc.getResult();
				if (null != resultString && !"".equals(resultString)) {
					return resultString;
				}
			} else {
				logger.info("返回http状态码:{}，请检查请求报文或者请求地址是否正确", status);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 功能：前台交易构造HTTP POST自动提交表单<br>
	 * @param action 表单提交地址<br>
	 * @param hiddens 以MAP形式存储的表单键值<br>
	 * @param encoding 上送请求报文域encoding字段的值<br>
	 * @return 构造好的HTTP POST交易表单<br>
	 */
	public static String createAutoFormHtml(String reqUrl, Map<String, String> hiddens, String encoding) {
		StringBuffer sf = new StringBuffer();
		sf.append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=" + encoding + "\"/></head><body>");
		sf.append("<form id = \"pay_form\" action=\"" + reqUrl + "\" method=\"post\">");
		if (null != hiddens && 0 != hiddens.size()) {
			Set<Entry<String, String>> set = hiddens.entrySet();
			Iterator<Entry<String, String>> it = set.iterator();
			while (it.hasNext()) {
				Entry<String, String> ey = it.next();
				String key = ey.getKey();
				String value = ey.getValue();
				sf.append("<input type=\"hidden\" name=\"" + key + "\" id=\"" + key + "\" value=\"" + value + "\"/>");
			}
		}
		sf.append("</form>");
		sf.append("</body>");
		sf.append("<script type=\"text/javascript\">");
		sf.append("document.all.pay_form.submit();");
		sf.append("</script>");
		sf.append("</html>");
		return sf.toString();
	}

	/**
	 * 功能：将批量文件内容使用DEFLATE压缩算法压缩，Base64编码生成字符串并返回<br>
	 * 适用到的交易：批量代付，批量代收，批量退货<br>
	 * @param filePath 批量文件-全路径文件名<br>
	 * @return
	 */
	public static String enCodeFileContent(String filePath, String encoding) {
		String baseFileContent = "";

		File file = new File(filePath);
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		InputStream in = null;
		try {
			in = new FileInputStream(file);
			int fl = in.available();
			if (null != in) {
				byte[] s = new byte[fl];
				in.read(s, 0, fl);
				// 压缩编码.
				baseFileContent = new String(SecureUtil.base64Encode(SecureUtil.deflater(s)), encoding);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (null != in) {
				try {
					in.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return baseFileContent;
	}

	/**
	 * 功能：解析交易返回的fileContent字符串并落地 （ 解base64，解DEFLATE压缩并落地）<br>
	 * 适用到的交易：对账文件下载，批量交易状态查询<br>
	 * @param resData 返回报文map<br>
	 * @param fileDirectory 落地的文件目录（绝对路径）
	 * @param encoding 上送请求报文域encoding字段的值<br>	
	 */
	public static void deCodeFileContent(Map<String, String> resData, String fileDirectory, String encoding) {
		// 解析返回文件
		String fileContent = resData.get(SDKConstants.param_fileContent);
		if (null != fileContent && !"".equals(fileContent)) {
			try {
				byte[] fileArray = SecureUtil.inflater(SecureUtil.base64Decode(fileContent.getBytes(encoding)));
				String filePath = null;
				if (SDKUtil.isEmpty(resData.get("fileName"))) {
					filePath =
							fileDirectory + File.separator + resData.get("merId") + "_" + resData.get("batchNo") + "_" + resData.get("txnTime")
									+ ".txt";
				} else {
					filePath = fileDirectory + File.separator + resData.get("fileName");
				}
				File file = new File(filePath);
				if (file.exists()) {
					file.delete();
				}
				file.createNewFile();
				FileOutputStream out = new FileOutputStream(file);
				out.write(fileArray, 0, fileArray.length);
				out.flush();
				out.close();

			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 将结果文件内容 转换成明文字符串：解base64,解压缩<br>
	 * 适用到的交易：批量交易状态查询<br>
	 * @param fileContent 批量交易状态查询返回的文件内容<br>
	 * @return 内容明文<br>
	 */
	public static String getFileContent(String fileContent, String encoding) {
		String fc = "";
		try {
			fc = new String(SecureUtil.inflater(SecureUtil.base64Decode(fileContent.getBytes())), encoding);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return fc;
	}

	/**
	 * 持卡人信息域customerInfo构造<br>
	 * 说明：不勾选对敏感信息加密权限使用旧的构造customerInfo域方式，不对敏感信息进行加密（对 phoneNo，cvn2， expired不加密），但如果送pin的话则加密<br>
	 * @param customerInfoMap 信息域请求参数 key送域名value送值,必送<br>
	 *        例如：customerInfoMap.put("certifTp", "01");					//证件类型<br>
				  customerInfoMap.put("certifId", "341126197709218366");	//证件号码<br>
				  customerInfoMap.put("customerNm", "互联网");				//姓名<br>
				  customerInfoMap.put("phoneNo", "13552535506");			//手机号<br>
				  customerInfoMap.put("smsCode", "123456");					//短信验证码<br>
				  customerInfoMap.put("pin", "111111");						//密码（加密）<br>
				  customerInfoMap.put("cvn2", "123");           			//卡背面的cvn2三位数字（不加密）<br>
				  customerInfoMap.put("expired", "1711");  				    //有效期 年在前月在后（不加密)<br>
	 * @param accNo  customerInfoMap送了密码那么卡号必送,如果customerInfoMap未送密码pin，此字段可以不送<br>
	 * @param encoding 上送请求报文域encoding字段的值<br>				  
	 * @return base64后的持卡人信息域字段<br>
	 */
	public static String getCustomerInfo(Map<String, String> customerInfoMap, String accNo, String encoding, String path) {

		if (customerInfoMap.isEmpty())
			return "{}";
		StringBuffer sf = new StringBuffer("{");
		for (Iterator<String> it = customerInfoMap.keySet().iterator(); it.hasNext();) {
			String key = it.next();
			String value = customerInfoMap.get(key);
			if (key.equals("pin")) {
				if (null == accNo || "".equals(accNo.trim())) {
					logger.info("送了密码（PIN），必须在getCustomerInfoWithEncrypt参数中上传卡号");
					return "{}";
				} else {
					value = encryptPin(accNo, value, encoding, path);
				}
			}
			sf.append(key).append(SDKConstants.EQUAL).append(value);
			if (it.hasNext())
				sf.append(SDKConstants.AMPERSAND);
		}
		String customerInfo = sf.append("}").toString();
		logger.info("组装的customerInfo明文：" + customerInfo);
		try {
			return new String(SecureUtil.base64Encode(sf.toString().getBytes(encoding)), encoding);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return customerInfo;
	}

	/**
	 * 持卡人信息域customerInfo构造，勾选对敏感信息加密权限 适用新加密规范，对pin和phoneNo，cvn2，expired加密 <br>
	 * 适用到的交易： <br>
	 * @param customerInfoMap 信息域请求参数 key送域名value送值,必送 <br>
	 *        例如：customerInfoMap.put("certifTp", "01");					//证件类型 <br>
				  customerInfoMap.put("certifId", "341126197709218366");	//证件号码 <br>
				  customerInfoMap.put("customerNm", "互联网");				//姓名 <br>
				  customerInfoMap.put("smsCode", "123456");					//短信验证码 <br>
				  customerInfoMap.put("pin", "111111");						//密码（加密） <br>
				  customerInfoMap.put("phoneNo", "13552535506");			//手机号（加密） <br>
				  customerInfoMap.put("cvn2", "123");           			//卡背面的cvn2三位数字（加密） <br>
				  customerInfoMap.put("expired", "1711");  				    //有效期 年在前月在后（加密） <br>
	 * @param accNo  customerInfoMap送了密码那么卡号必送,如果customerInfoMap未送密码PIN，此字段可以不送<br>
	 * @param encoding 上送请求报文域encoding字段的值
	 * @return base64后的持卡人信息域字段 <br>
	 */
	public static String getCustomerInfoWithEncrypt(Map<String, String> customerInfoMap, String accNo, String encoding, String path) {
		if (customerInfoMap.isEmpty())
			return "{}";
		StringBuffer sf = new StringBuffer("{");
		//敏感信息加密域
		StringBuffer encryptedInfoSb = new StringBuffer("");

		for (Iterator<String> it = customerInfoMap.keySet().iterator(); it.hasNext();) {
			String key = it.next();
			String value = customerInfoMap.get(key);
			if (key.equals("phoneNo") || key.equals("cvn2") || key.equals("expired")) {
				encryptedInfoSb.append(key).append(SDKConstants.EQUAL).append(value).append(SDKConstants.AMPERSAND);
			} else {
				if (key.equals("pin")) {
					if (null == accNo || "".equals(accNo.trim())) {
						logger.info("送了密码（PIN），必须在getCustomerInfoWithEncrypt参数中上传卡号");
						return "{}";
					} else {
						value = encryptPin(accNo, value, encoding, path);
					}
				}
				sf.append(key).append(SDKConstants.EQUAL).append(value).append(SDKConstants.AMPERSAND);
			}
		}

		if (!encryptedInfoSb.toString().equals("")) {
			encryptedInfoSb.setLength(encryptedInfoSb.length() - 1);//去掉最后一个&符号
			logger.info("组装的customerInfo encryptedInfo明文:{}", encryptedInfoSb.toString());
			sf.append("encryptedInfo").append(SDKConstants.EQUAL).append(encryptData(encryptedInfoSb.toString(), encoding, path));
		} else {
			sf.setLength(sf.length() - 1);
		}

		String customerInfo = sf.append("}").toString();
		logger.info("组装的customerInfo明文:{}", customerInfo);
		try {
			return new String(SecureUtil.base64Encode(sf.toString().getBytes(encoding)), encoding);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return customerInfo;
	}

	/**
	 * 解析返回报文（后台通知）中的customerInfo域：解base64,如果带敏感信息加密 encryptedInfo 则将其解密并将 encryptedInfo中的域放到customerInfoMap返回
	 * @param customerInfo
	 * @param encoding
	 * @return
	 */
	public static Map<String, String> parseCustomerInfo(String customerInfo, String encoding, String path, String pwd, String type) {
		Map<String, String> customerInfoMap = null;
		try {
			byte[] b = SecureUtil.base64Decode(customerInfo.getBytes(encoding));
			String customerInfoNoBase64 = new String(b, encoding);
			logger.info("解base64后===>{}", customerInfoNoBase64);
			//去掉前后的{}
			customerInfoNoBase64 = customerInfoNoBase64.substring(1, customerInfoNoBase64.length() - 1);
			customerInfoMap = SDKUtil.parseQString(customerInfoNoBase64);
			if (customerInfoMap.containsKey("encryptedInfo")) {
				String encInfoStr = customerInfoMap.get("encryptedInfo");
				customerInfoMap.remove("encryptedInfo");
				String encryptedInfoStr = decryptData(encInfoStr, encoding, path, pwd, type);
				Map<String, String> encryptedInfoMap = SDKUtil.parseQString(encryptedInfoStr);
				customerInfoMap.putAll(encryptedInfoMap);
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return customerInfoMap;
	}

	/**
	 * 密码加密并做base64<br>
	 * @param accNo 卡号<br>
	 * @param pwd 密码<br>
	 * @param encoding<br>
	 * @return 加密的内容<br>
	 */
	public static String encryptPin(String accNo, String pwd, String encoding, String path) {
		return SecureUtil.EncryptPin(pwd, accNo, encoding, CertUtil.getEncryptCertPublicKey(path));
	}

	/**
	 * 敏感信息加密并做base64(卡号，手机号，cvn2,有效期）<br>
	 * @param data 送 phoneNo,cvn2,有效期<br>
	 * @param encoding<br>
	 * @return 加密的密文<br>
	 */
	public static String encryptData(String data, String encoding, String path) {
		return SecureUtil.EncryptData(data, encoding, CertUtil.getEncryptCertPublicKey(path));
	}

	/**
	 * 敏感信息解密
	 * @param base64EncryptedInfo
	 * @param encoding
	 * @return 解密后的明文
	 */
	public static String decryptData(String base64EncryptedInfo, String encoding, String path, String pwd, String type) {
		return SecureUtil.DecryptedData(base64EncryptedInfo, encoding, CertUtil.getSignCertPrivateKey(path, pwd, type));
	}

	/**
	 * 加密磁道信息，使用公钥文件<br>
	 * @param trackData 待加密磁道数据<br>
	 * @param encoding 编码格式<br>
	 * @return 加密的密文<br>
	 
	public static String encryptTrack(String trackData, String encoding) {
		return SecureUtil.EncryptData(trackData, encoding, CertUtil.getEncryptTrackPublicKey());
	}*/

	/**
	 * 加密磁道信息，使用模和指数<br>
	 * @param trackData 待加密磁道数据<br>
	 * @param encoding 编码格式<br>
	 * @param modulus 模<br>
	 * @param exponent 指数<br>
	 * @return 加密的密文<br>
	 
	public static String encryptTrack(String trackData, String encoding, String modulus, String exponent) {
		return SecureUtil.EncryptData(trackData, encoding, CertUtil.getEncryptTrackCertPublicKey(modulus, exponent));
	}*/

	/**
	 * 获取敏感信息加密证书的物理序列号<br>
	 * @return
	 */
	public static String getEncryptCertId(String path) {
		return CertUtil.getEncryptCertId(path);
	}

	/**
	 * 对字符串做base64
	 * @param rawStr
	 * @param encoding
	 * @return
	 * @throws IOException
	 */
	public static String base64Encode(String rawStr, String encoding) throws IOException {
		byte[] rawByte = rawStr.getBytes(encoding);
		return new String(SecureUtil.base64Encode(rawByte), encoding);
	}

	/**
	 * 对base64的字符串解base64
	 * @param base64Str
	 * @param encoding
	 * @return
	 * @throws IOException
	 */
	public static String base64Decode(String base64Str, String encoding) throws IOException {
		byte[] rawByte = base64Str.getBytes(encoding);
		return new String(SecureUtil.base64Decode(rawByte), encoding);
	}

	/**
	 * 将Map中的数据转换成key1=value1&key2=value2的形式 不包含签名域signature
	 * 
	 * @param data
	 *            待拼接的Map数据
	 * @return 拼接好后的字符串
	 */
	public static String coverMap2String(Map<String, String> data) {
		TreeMap<String, String> tree = new TreeMap<String, String>();
		Iterator<Entry<String, String>> it = data.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, String> en = it.next();
			if (SDKConstants.param_signature.equals(en.getKey().trim())) {
				continue;
			}
			tree.put(en.getKey(), en.getValue());
		}
		it = tree.entrySet().iterator();
		StringBuffer sf = new StringBuffer();
		while (it.hasNext()) {
			Entry<String, String> en = it.next();
			sf.append(en.getKey() + SDKConstants.EQUAL + en.getValue() + SDKConstants.AMPERSAND);
		}
		return sf.substring(0, sf.length() - 1);
	}

}