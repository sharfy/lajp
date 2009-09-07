package phpjava;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次请求服务线程
 * @author diaoyf
 *
 */
class SingleThread extends Thread
{
	/** PHP字符集 */
	final static String PHP_CHARSET = "UTF-8";
	/**
	 * 合法的request消息包最小长度
	 * 最小包格式： a:3:{i:0;i:1;i:1;i:1;i:2;s:17:"a:1:{i:0;s:0:"";}";}
	 */
	final static int REQUEST_MIN_LEN = 51;
	
	/** 请求进程消息类型id */
	int processId;
	/** 消息buffer */
	byte[] buffer = new byte[8192];
	
	/** 参数字节数组 */
	byte[] args;
	/** 参数字节数组有效长度 */
	int argsLen;
	/** 参数字节数组指针,指向数组中有效字节的下一个 */
	int sp = 0;
	
	
	
	/** 参数Node树(包含调用的类名和方法名称) */
	ArgsNode argsTree;
	
	/** 请求的类名 */
	String clazzName;
	/** 请求的方法名称 */
	String methodName;
	/** 参数类型数组 */
	Class[] argsClazz;
	/** 参数值数组 */
	Object[] argsValue;
	
	/** 应答 */
	byte[] callBack;
	
	public SingleThread(int processId)
	{
		this.processId = processId;
	}
	
	@Override
	public void run()
	{
		//--
		System.out.println("线程启动");
		
		//---------------------------------------------------
		//	1.发送握手回应(0长度消息)
		//---------------------------------------------------
		if (MsgQ.msgsnd(PhpJava.msqid, processId + 1, new byte[0], 0) != 0)
		{
			System.out.println("[LAJP Error(warn)]: HandShake send error.");
			return;
		}		
		
		
		//---------------------------------------------------
		//	2.接受消息
		//---------------------------------------------------
		//接受消息1
		int bufLen = MsgQ.msgrcv(PhpJava.msqid, buffer, buffer.length, processId);		
		//--
		System.out.println("接收消息1:" + new String(buffer, 0, bufLen));
		
		//request最小长度检查
		if (bufLen < REQUEST_MIN_LEN)
		{
			//构建异常消息
			byte[] exMsg = exceptionRsp("request message error: length < REQUEST_MIN_LEN");
			//发送异常应答
			MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
			
			return; 
		}
		
		//解析第一个request
		//拆分数量
		int a = 11; 								//拆分数量起始下标
		int b = nextIndex(buffer, (byte)0x3b, a);	//拆分数量结束";"下标
		int split_count = Integer.parseInt(new String(buffer, a, b - a));
		//拆分序列
		a = nextIndex(buffer, (byte)0x3a, b + 1);
		a = nextIndex(buffer, (byte)0x3a, a + 1) + 1; 	
		b = nextIndex(buffer, (byte)0x3b, a);
		int split_seq = Integer.parseInt(new String(buffer, a, b - a));
		//本次消息体长度
		a = nextIndex(buffer, (byte)0x73, b + 1) + 2;	//"s"+2的位置
		b = nextIndex(buffer, (byte)0x3a, a + 1);		//消息体长度结束":"下标
		int split_msg_len = Integer.parseInt(new String(buffer, a, b - a));
		//本次消息起始下标
		int split_msg_start = b + 2;
		
		//--
		System.out.printf("拆分数量:%d,拆分序列:%d,本次消息体长度:%d,消息:%s\n", 
				split_count, split_seq, split_msg_len, new String(buffer, split_msg_start, split_msg_len));
		
		//根据拆分数量初始化
		args = new byte[split_count * PhpJava.PHPJAVA_MSG_MAX + split_count * 128];
		//将第一个request消息体copy到args
		System.arraycopy(buffer, split_msg_start, args, 0, split_msg_len); 
		//args有效长度
		argsLen += split_msg_len;
		
		//解析其他(第二个以后)request----------------------
		for (int i = 1; i < split_count; i++)
		{
			//接受消息n
			bufLen = MsgQ.msgrcv(PhpJava.msqid, buffer, buffer.length, processId);		
			//--
			System.out.printf("接收消息[%d]:%s\n:", i, new String(buffer, 0, bufLen));
			
			//request最小长度检查
			if (bufLen < REQUEST_MIN_LEN)
			{
				//构建异常消息
				byte[] exMsg = exceptionRsp("request message error: length < REQUEST_MIN_LEN");
				//发送异常应答
				MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
				
				return; 
			}
			
			//本次消息体长度
			a = nextIndex(buffer, (byte)0x73, 0);			//消息体长度起始位置
			b = nextIndex(buffer, (byte)0x3a, a + 1);		//消息体长度结束":"下标
			split_msg_len = Integer.parseInt(new String(buffer, a, b - a));
			//本次消息起始下标
			split_msg_start = b + 2;
			
			//将本次request消息体copy到args
			System.arraycopy(buffer, split_msg_start, args, 0, split_msg_len); 
			//args有效长度
			argsLen += split_msg_len;
		}

		//---------------------------------------------------
		//	3.参数数量分析
		//---------------------------------------------------
		a = nextIndex(args, (byte)0x3a, 0) + 1;		//第一个":"下一个
		b = nextIndex(args, (byte)0x3a, a);			//第二个":"
		int argsCount = Integer.parseInt(new String(args, a, b-a));
		//初始化节点树跟节点(type=数组，name=null，value=参数数量)
		argsTree = ArgsNode.createNode("a", null, argsCount);
		//指针指向第一个子节点：调用java类和方法名称
		sp = nextIndex(args, (byte)0x7b, 0) + 1; //"{"的下一个
		
		//---------------------------------------------------
		//	4.解析参数数组,构建argsTree结构
		//---------------------------------------------------
		try
		{
			parseArgs(argsTree);
		}
		catch (Exception e)
		{
			//解析出错
			e.printStackTrace();
			
			//构建异常消息
			byte[] exMsg = exceptionRsp("request message error: " + e.getMessage());
			//发送异常应答
			MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
			
			return; 
		}

		//--测试参数数组解析结果
		testArgsTree(argsTree, 0);
		
		
		//---------------------------------------------------
		//	5.解析ArgsTree结构,构建调用Java服务方法的：
		//		类名
		//		方法名
		//		方法参数类型数组
		//		方法参数值数组
		//---------------------------------------------------
		try
		{
			parseArgsTree();
		}
		catch (Exception e)
		{
			//构建异常消息
			byte[] exMsg = exceptionRsp("request message error: " + e.getMessage());
			//发送异常应答
			MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
			
			return; 
		}

		//---------------------------------------------------
		//	6.调用Java服务方法
		//---------------------------------------------------
		Class clazz = null;
		try
		{
			clazz = Class.forName(clazzName);
		}
		catch (ClassNotFoundException e)
		{
			e.printStackTrace();
			//构建异常消息
			byte[] exMsg = exceptionRsp("request message error: Can't find class " + clazzName);
			//发送异常应答
			MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
			
			return; 
		}
		
		//获得调用的方法
		Method method = null;
		try
		{
			method = clazz.getMethod(methodName, argsClazz);
		}
		catch (SecurityException e)
		{
			e.printStackTrace();
			//构建异常消息
			byte[] exMsg = exceptionRsp("request message error: SecurityException for " + clazzName + "." + method.getName());
			//发送异常应答
			MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
			
			return; 
		}
		catch (NoSuchMethodException e)
		{
			e.printStackTrace();
			//构建异常消息
			byte[] exMsg = exceptionRsp("request message error: Can't find method " + clazzName + "." + method.getName());
			//发送异常应答
			MsgQ.msgsnd(PhpJava.msqid, processId + 1, exMsg, exMsg.length);
			
			return; 
		}
		
		//调用
		Object obj = null; //方法返回
		try
		{
			obj = method.invoke(null, argsValue);
		}
		catch (IllegalArgumentException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		catch (IllegalAccessException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		catch (InvocationTargetException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		//---------------------------------------------------
		//	7.转换Java服务方法返回值到Php序列化数据
		//---------------------------------------------------
		
		//---------------------------------------------------
		//	8.构建并发送response消息包
		//---------------------------------------------------
		
		System.out.println("线程关闭");
	}
	
	/**
	 * 解析php序列化参数数组
	 * @param father 父节点(数组或对象)
	 */
	private void parseArgs(ArgsNode father) throws Exception
	{
		//处理所有的子节点
		NEXT: while (true)
		{
			//到结束了
			if (sp >= argsLen)
			{
				return;
			}
			
			if (args[sp] == 0x7d)	//"}" 本层结束
			{
				sp++;
				break;
			}

			//下标-----------------------------------------------
			byte nameType = args[sp];	//"下标"类型
			String name = null;			//"下标"
			switch (nameType)
			{
				case 0x69: 		//i 整形
					int a = sp + 2;							//"下标"起始
					sp = nextIndex(args, (byte)0x3b, a); 	//"下标"结束";"
					sp++;									//"值"类型起始
					break;
				case 0x73:		//s 字符串
					a = sp + 2;								//"下标"长度起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"下标"长度结束":"
					int len = Integer.parseInt(new String(args, a, sp - a)); //"下标"长度
					a = sp + 2;								//"下标"起始(掠过引号)
					sp = a + len;							//"下标"结束(结束引号)
					name = new String(args, a, sp - a);		//"下标"
					sp = sp + 2;							//"值"类型起始			
					break;
				default:
					throw new Exception("index[" + nameType + "] must be 'i' or 's'");
			}

			//值-----------------------------------------------
			byte valueType = args[sp];	//"值"类型
			//类型
			switch (valueType)
			{
				case 0x69: 		//i 整形
					int a = sp + 2;							//"值"起始
					sp = nextIndex(args, (byte)0x3b, a); 	//"值"结束";"
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("i", name, Integer.parseInt(new String(args, a, sp - a))));
					sp = sp + 1;							//下一个
					continue NEXT;
				case 0x64:		//d 浮点
					a = sp + 2;								//"值"起始
					sp = nextIndex(args, (byte)0x3b, a); 	//"值"结束";"
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("d", name, Double.parseDouble(new String(args, a, sp - a))));
					sp = sp + 1;							//下一个
					continue NEXT;
				case 0x62:		//b 布尔
					a = sp + 2;								//"值"
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("b", name, args[a] == 0x31 ? true : false));
					sp = a + 2;								//下一个
					continue NEXT;
				case 0x73:		//s 字符串
					a = sp + 2;								//字符串长度起始
					sp = nextIndex(args, (byte)0x3a, a); 	//字符串长度结束":"
					int len = Integer.parseInt(new String(args, a, sp - a)); //字符串长度
					a = sp + 2;								//字符串起始(掠过引号)
					
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("s", name, new String(args, a, len)));
					
					sp = a + len;							//字符串结束(结束引号)
					sp = sp + 2;							//下一个				
					continue NEXT;
				case 0x61:		//a 数组
					a = sp + 2;								//"数组长度"起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"数组长度"结束":"
					int arrayLen = Integer.parseInt(new String(args, a, sp -a));
					//ArgsNode对象(value=数组长度)
					ArgsNode arrayNode = ArgsNode.createNode("a", name, arrayLen);
					father.addChild(arrayNode);	
					sp = sp + 2;							//数组第一个元素(掠过{)				
					//递归处理子节点
					parseArgs(arrayNode);
					continue NEXT;
				case 0x4f:		//O 对象
					a = sp + 2;								//"对象类型长度"起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"对象类型长度"结束":"
					len = Integer.parseInt(new String(args, a, sp - a)); //"对象类型长度"长度
					a = sp + 2;								//"对象类型"起始(掠过引号)
					//ArgsNode对象(有值：类型)
					ArgsNode objNode = ArgsNode.createNode("O", name, new String(args, a, len));
					father.addChild(objNode);
					a = a + len + 2;						//"对象属性长度"起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"对象属性长度"结束":"
					sp = sp + 2;							//对象第一个属性(掠过{)
					//递归处理子节点
					parseArgs(objNode);
					continue NEXT;
				default:
					throw new Exception("index[" + valueType + "] must be 'i','d','b','s','a','O'.");
			}

		}		
	}
	
	/**
	 * 解析ArgsTree结构，解析出调用Java的条件：
	 * <li>类名</li>
	 * <li>方法名</li>
	 * <li>方法参数类型数组</li>
	 * <li>方法参数值数组</li>
	 * @throws Exception 
	 */
	private void parseArgsTree() throws Exception
	{
		//获取调用类和方法名----------------------------------
		String clazzMethod = (String)argsTree.subList.get(0).Value;
		int coloncolonIndex = clazzMethod.indexOf("::");
		clazzName = clazzMethod.substring(0, coloncolonIndex);
		methodName = clazzMethod.substring(coloncolonIndex + 2);
		
		//--
		System.out.printf("调用类名:%s,调用方法名:%s\n", clazzName, methodName);
		
		
		//初始化"方法参数类型数组"、"方法参数值数组"
		argsClazz = new Class[argsTree.subList.size() - 1];
		argsValue = new Object[argsTree.subList.size() - 1];

		
		//获取方法参数类型数组---------------------------------
		for (int i = 0; i < argsClazz.length; i++)
		{
			//当前节点
			ArgsNode currentNode = argsTree.subList.get(i + 1);
			//当前节点类型
			String type = currentNode.type;
			if (type.equals("i"))
			{
				argsClazz[i] = int.class;
			}
			else if (type.equals("d"))
			{
				argsClazz[i] = double.class;
			}
			else if (type.equals("b"))
			{
				argsClazz[i] = boolean.class;
			}
			else if (type.equals("s"))
			{
				argsClazz[i] = java.lang.String.class;
			}
			else if (type.equals("a"))
			{
				//以数据第一个元素的key类型为依据:
				//	如果是"i"(在ArgsNode节点中不存储name),对应java.util.List,
				//	如果是"s"(在ArgsNode节点中key存储在name中),对应java.util.Map。
				//如果数组长度为0，对应java.util.List
				if (currentNode.subList.size() > 0)
				{
					String subNodeName = currentNode.subList.get(0).name;
					if (subNodeName == null)
					{
						argsClazz[i] = java.util.List.class;
					}
					else
					{
						argsClazz[i] = java.util.Map.class;
					}
				}
				else
				{
					argsClazz[i] = java.util.List.class;
				}
			}
			else if (type.equals("O"))
			{
				//类名
				String phpClazzName = (String)currentNode.Value;
				String javaClazzName = phpClazzName.replace('_', '.');
				//将"-"替换为"."
				try
				{
					argsClazz[i] = Class.forName(javaClazzName);
				}
				catch (ClassNotFoundException e)
				{
					//解析出错
					e.printStackTrace();
					throw new Exception("Can't find Class " + javaClazzName + " in Java.");
					
				}
			}
		}
		
		//获取方法参数值数组---------------------------------
		for (int i = 0; i < argsValue.length; i++)
		{
			argsValue[i] = parseArgsNodeValue(argsTree.subList.get(i + 1));
		}
		
		
	}
	
	/**
	 * 将ArgsNode节点及其子节点解析为java对象
	 * @param node 要解析的节点
	 * @return 解析出的java对象
	 */
	private Object parseArgsNodeValue(ArgsNode node) throws Exception
	{
		if (node.type.equals("i"))
		{
			return (Integer)node.Value;
		}
		else if (node.type.equals("d"))
		{
			return (Double)node.Value;
		}
		else if (node.type.equals("b"))
		{
			return (Boolean)node.Value;
		}
		else if (node.type.equals("s"))
		{
			return (String)node.Value;
		}
		else if (node.type.equals("a"))
		{
			Object retObj = null;
			
			//以数据第一个元素的key类型为依据:
			//	如果是"i"(在ArgsNode节点中不存储name),对应java.util.List,
			//	如果是"s"(在ArgsNode节点中key存储在name中),对应java.util.Map。
			//如果数组长度为0，对应java.util.List
			if (node.subList.size() > 0)
			{
				String subNodeName = node.subList.get(0).name;
				if (subNodeName == null)
				{
					List list = new ArrayList();
					for (ArgsNode subNode : node.subList)
					{
						list.add(parseArgsNodeValue(subNode));
					}
					
					return list;
				}
				else
				{
					Map map = new HashMap();
					for (ArgsNode subNode : node.subList)
					{
						map.put(subNode.name, parseArgsNodeValue(subNode));
					}
					
					return map;
				}
			}
			else
			{
				return new ArrayList();
			}
		}
		else if (node.type.equals("O"))
		{
			//类名
			String phpClazzName = (String)node.Value;
			String javaClazzName = phpClazzName.replace('-', '.');
			try
			{
				//实例化对象
				Object retObj = Class.forName(javaClazzName).newInstance();
				//对象有属性
				if (node.subList.size() > 0)
				{
					//设置属性
					for (ArgsNode subNode : node.subList)
					{
						//属性:基本数据类型
						if (subNode.type.equals("i") || subNode.type.equals("d")
								|| subNode.type.equals("b") || subNode.type.equals("s"))
						{
							javaBeanSetXXX(retObj, subNode.name, subNode.Value);
						}
						//属性:集合或对象
						else if (subNode.type.equals("a") || subNode.type.equals("O"))
						{
							javaBeanSetXXX(retObj, subNode.name, parseArgsNodeValue(subNode));
						}
					}
					
				}
				
				return retObj;
			}
			catch (InstantiationException e)
			{
				e.printStackTrace();
				throw new Exception("Can't create instantiation of Class: " + javaClazzName);
			}
			catch (IllegalAccessException e)
			{
				e.printStackTrace();
				throw new Exception("Illega access of Class: " + javaClazzName);
			}
			catch (ClassNotFoundException e)
			{
				e.printStackTrace();
				throw new Exception("Can't find class: " + javaClazzName);
			}
		}
		else 
		{
			return null;
		}
	}
	
	/**
	 * 设置javaBean对象属性
	 * @param javaBean javaBean对象
	 * @param attributeName 属性名称
	 * @param value 值
	 * @throws Exception
	 */
	private void javaBeanSetXXX(
			Object javaBean, 
			String attributeName, 
			Object value) throws Exception 
	{
		BeanInfo beanInfo;
		try
		{
			beanInfo = Introspector.getBeanInfo(javaBean.getClass(), Object.class);
		}
		catch (IntrospectionException e)
		{
			//内省异常
			e.printStackTrace();
			throw new Exception("IntrospectionException for " + javaBean.getClass());
		}
		
		//获得javaBean属性集
		PropertyDescriptor[] pds = beanInfo.getPropertyDescriptors();
		
		for (PropertyDescriptor pd : pds) 
		{
			if(pd.getName().equals(attributeName));
			{
				try
				{
					//执行set方法
					pd.getWriteMethod().invoke(javaBean, value);
					break;
				}
				catch (IllegalArgumentException e)
				{
					e.printStackTrace();
					throw new Exception("IllegalArgumentException for " + javaBean.getClass() + "." + attributeName);
				}
				catch (IllegalAccessException e)
				{
					e.printStackTrace();
					throw new Exception("IllegalAccessException for " + javaBean.getClass() + "." + attributeName);
				}
				catch (InvocationTargetException e)
				{
					e.printStackTrace();
					throw new Exception("InvocationTargetException for " + javaBean.getClass() + "." + attributeName);
				}
			}
		}
	}
	
	/**
	 * 在"buf"数组中查找下一个"c", 从start开始
	 * @param buf
	 * @param c
	 * @param start
	 * @return 返回下标，如果查询不到返回-1
	 */
	private int nextIndex(byte[] buf, byte c, int start)
	{
		for (int index = start; index < buf.length; index++)
		{
			if (buf[index] == c)
			{
				return index;
			}
		}
		
		return -1;
	}
	

	
	/**
	 * java服务方法返回值转换为php序列化数据，并以callBack存储。
	 * @param obj
	 */
	private void javaSeriallze2Php(Object obj)
	{
		
	}
	
	/**
	 * 构建异常Response消息包
	 * @param exMsg 异常信息
	 * @return
	 */
	private byte[] exceptionRsp(String exMsg)
	{
		//异常信息字节数组
		byte[] ex = exMsg.getBytes();
		//异常信息长度字节数组
		byte[] exLen = null;
		try
		{
			exLen = ("" + ex.length).getBytes(PHP_CHARSET);
		}
		catch (UnsupportedEncodingException e)
		{
			e.printStackTrace();
		}
		
		//异常信息(php序列化string)
		byte[] exBytes = new byte[2 + exLen.length + 2 + ex.length + 2];
		exBytes[0] = 0x73;										//s
		exBytes[1] = 0x3a;										//:
		System.arraycopy(exLen, 0, exBytes, 2, exLen.length);	//长度
		exBytes[2 + exLen.length] = 0x3a;						//:
		exBytes[3 + exLen.length] = 0x22;						//"
		System.arraycopy(ex, 0, exBytes, 3 + exLen.length + 1, ex.length);
		exBytes[4 + exLen.length + ex.length] = 0x22;			//"
		exBytes[5 + exLen.length + ex.length] = 0x3b;			//;
		
		
		//初始化异常消息
		byte[] ret = new byte[26 + exBytes.length];
		ret[0] = 0x61;	//a
		ret[1] = 0x3a;	//:
		ret[2] = 0x33;	//3
		ret[3] = 0x3a;	//:
		ret[4] = 0x7b;	//{
		ret[5] = 0x69;	//i  //元素1
		ret[6] = 0x3a;	//:
		ret[7] = 0x30;	//0
		ret[8] = 0x3b;	//;
		ret[9] = 0x69;	//i
		ret[10] = 0x3a;	//:
		ret[11] = 0x30;	//0
		ret[12] = 0x3b;	//;
		ret[13] = 0x69;	//i  //元素2
		ret[14] = 0x3a;	//:
		ret[15] = 0x31;	//1
		ret[16] = 0x3b;	//;
		ret[17] = 0x69;	//i
		ret[18] = 0x3a;	//:
		ret[19] = 0x31;	//1
		ret[20] = 0x3b;	//;
		ret[21] = 0x69;	//i  //元素3
		ret[22] = 0x3a;	//:
		ret[23] = 0x32;	//2
		ret[24] = 0x3b;	//;
		System.arraycopy(exBytes, 0, ret, 25, exBytes.length); //异常信息(php序列化string)
		ret[ret.length - 1] = 0x7d;	//}
		
		return ret;
	}
	
	/**
	 * 测试
	 * @param node
	 * @param tab 缩进层级
	 */
	private void testArgsTree(ArgsNode node, int tab)
	{
		String tabCount = "";
		for (int i = 0; i < tab; i++)
		{
			tabCount += "\t";
		}
		System.out.printf("%stype=%s,name=%s,value=%s\n", tabCount, node.type, node.name, node.Value);

		if (node.type.equals("a") || node.type.equals("O"))
		{
			for (ArgsNode subNode : node.subList)
			{
				testArgsTree(subNode, tab + 1);
			}
		}
	}
}
