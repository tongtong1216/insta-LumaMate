# 第三方组件说明

本后端使用以下 Python 组件。版本来自 requirements*.txt，许可证标识来自已安装发行包元数据；完整版权声明与许可证正文随对应发行包提供，分发时应一并保留。表内包括直接依赖和锁定的传递依赖。

| 组件 | 版本 | 许可证元数据 |
| --- | --- | --- |
| [annotated-doc](https://pypi.org/project/annotated-doc/0.0.5/) | 0.0.5 | MIT |
| [annotated-types](https://pypi.org/project/annotated-types/0.8.0/) | 0.8.0 | MIT |
| [anyio](https://pypi.org/project/anyio/4.15.1/) | 4.15.1 | MIT |
| [certifi](https://pypi.org/project/certifi/2026.7.22/) | 2026.7.22 | MPL-2.0 |
| [click](https://pypi.org/project/click/8.5.0/) | 8.5.0 | BSD-3-Clause |
| [distro](https://pypi.org/project/distro/1.9.0/) | 1.9.0 | Apache License, Version 2.0 |
| [fastapi](https://pypi.org/project/fastapi/0.141.1/) | 0.141.1 | MIT |
| [h11](https://pypi.org/project/h11/0.16.0/) | 0.16.0 | MIT |
| [httpcore](https://pypi.org/project/httpcore/1.0.9/) | 1.0.9 | BSD-3-Clause |
| [httpx](https://pypi.org/project/httpx/0.28.1/) | 0.28.1 | BSD-3-Clause |
| [idna](https://pypi.org/project/idna/3.20/) | 3.20 | BSD-3-Clause |
| [iniconfig](https://pypi.org/project/iniconfig/2.3.0/) | 2.3.0 | MIT |
| [jiter](https://pypi.org/project/jiter/0.17.0/) | 0.17.0 | MIT |
| [openai](https://pypi.org/project/openai/2.54.0/) | 2.54.0 | Apache-2.0 |
| [packaging](https://pypi.org/project/packaging/26.3/) | 26.3 | Apache-2.0 OR BSD-2-Clause |
| [pillow](https://pypi.org/project/pillow/12.3.0/) | 12.3.0 | MIT-CMU |
| [pluggy](https://pypi.org/project/pluggy/1.6.0/) | 1.6.0 | MIT |
| [pydantic](https://pypi.org/project/pydantic/2.13.5/) | 2.13.5 | MIT |
| [pydantic-core](https://pypi.org/project/pydantic-core/2.46.5/) | 2.46.5 | MIT |
| [pygments](https://pypi.org/project/pygments/2.21.0/) | 2.21.0 | BSD-2-Clause |
| [pytest](https://pypi.org/project/pytest/9.1.1/) | 9.1.1 | MIT |
| [python-dotenv](https://pypi.org/project/python-dotenv/1.2.3/) | 1.2.3 | BSD-3-Clause |
| [sniffio](https://pypi.org/project/sniffio/1.3.1/) | 1.3.1 | MIT OR Apache-2.0 |
| [starlette](https://pypi.org/project/starlette/1.6.0/) | 1.6.0 | BSD-3-Clause |
| [tqdm](https://pypi.org/project/tqdm/4.70.1/) | 4.70.1 | MPL-2.0 AND MIT |
| [typing-extensions](https://pypi.org/project/typing-extensions/4.16.0/) | 4.16.0 | PSF-2.0 |
| [typing-inspection](https://pypi.org/project/typing-inspection/0.4.4/) | 0.4.4 | MIT |
| [uvicorn](https://pypi.org/project/uvicorn/0.53.0/) | 0.53.0 | BSD-3-Clause |

## 外部服务

百炼为阿里云提供的外部模型服务，模型访问权限、地域和服务条款由所用账号与工作空间决定。默认 Mock 模式不调用百炼。OpenAI Python SDK 在此仅作为兼容接口客户端，不代表请求发送到 OpenAI。

此后端不包含 Insta360 SDK、模型权重或相机固件。
